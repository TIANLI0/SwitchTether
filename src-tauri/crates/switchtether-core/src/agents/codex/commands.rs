//! What a Codex shell command does, for the phone: Codex runs every file read, listing and
//! search through its shell tool, where Claude Code has `Read`, `LS`/`Glob` and `Grep`. A command
//! that only reads files becomes a `read` step whose files the phone can open; one that only
//! lists or searches becomes a `search` step. Anything else stays a `shell` step (the files it
//! clearly reads are still offered).
//!
//! Sources, best first: Codex's own parse (`commandActions` on app-server items, `parsed_cmd` in
//! rollouts), whose `unknown` entries (most PowerShell) are parsed again here; otherwise the
//! command text. The parse is deliberately narrow: a statement is only recognized when every
//! word of it is understood, so a write never shows as a read.

use serde_json::{json, Value};

use crate::agents::model::preview;

#[derive(Debug, Clone, PartialEq)]
pub struct FileRead {
    pub path: String,
    /// First line read (1-based) and the number of lines, when only part of the file was read.
    pub offset: Option<u64>,
    pub limit: Option<u64>,
}

impl FileRead {
    fn new(path: String) -> Self {
        Self {
            path,
            offset: None,
            limit: None,
        }
    }

    pub fn to_json(&self) -> Value {
        let mut v = json!({ "path": self.path });
        if let Some(o) = self.offset {
            v["offset"] = json!(o);
        }
        if let Some(l) = self.limit {
            v["limit"] = json!(l);
        }
        v
    }
}

#[derive(Debug, Clone, PartialEq)]
enum Stmt {
    Read(Vec<FileRead>),
    List,
    Search,
    /// Changes nothing the phone shows (`cd`, an encoding setting, an echoed separator).
    Neutral,
    Unknown,
}

/// The step a command makes: `category` is `read`, `search` or `shell`.
#[derive(Debug, Clone, PartialEq)]
pub struct Classified {
    pub category: &'static str,
    pub files: Vec<FileRead>,
}

impl Classified {
    /// Combines the parts of one step (code-mode `exec` runs several commands).
    pub fn merge(&mut self, other: Classified) {
        self.category = match (self.category, other.category) {
            ("shell", _) | (_, "shell") => "shell",
            ("read", _) | (_, "read") => "read",
            _ => "search",
        };
        for f in other.files {
            if !self.files.iter().any(|g| g.path == f.path) {
                self.files.push(f);
            }
        }
    }

    /// Short title of a read step: the file, or the names of the files.
    pub fn read_title(&self) -> Option<String> {
        match self.files.as_slice() {
            [] => None,
            [one] => Some(one.path.clone()),
            many => {
                let names: Vec<&str> = many.iter().map(|f| file_name(&f.path)).collect();
                Some(preview(&names.join(", "), 200).0)
            }
        }
    }

    /// Writes the result into a `tool_call`'s data: `category`, `files` and, for a read, the title.
    pub fn apply(&self, data: &mut Value) {
        data["category"] = json!(self.category);
        if !self.files.is_empty() {
            data["files"] = Value::Array(self.files.iter().map(FileRead::to_json).collect());
        }
        if self.category == "read" {
            if let Some(t) = self.read_title() {
                data["title"] = json!(t);
            }
        }
    }
}

fn file_name(path: &str) -> &str {
    path.trim_end_matches(['/', '\\'])
        .rsplit(['/', '\\'])
        .next()
        .unwrap_or(path)
}

/// Classifies a command from Codex's parse (`actions`: `commandActions` / `parsed_cmd`, either
/// spelling) or, without one, from its text. `cwd` resolves relative paths. None = nothing
/// recognized (a plain shell step).
pub fn classify(actions: Option<&Value>, command: &str, cwd: Option<&str>) -> Option<Classified> {
    let cwd = cwd.map(file_url_to_path);
    let stmts = match actions.and_then(Value::as_array).filter(|a| !a.is_empty()) {
        Some(list) => {
            let mut out = Vec::new();
            for a in list {
                out.extend(action_stmts(a, cwd.as_deref()));
            }
            out
        }
        None => script_stmts(&unwrap_shell(command), cwd.as_deref()),
    };
    summarize(stmts)
}

/// [classify] for an argv (`["bash", "-lc", "…"]`, `["pwsh", "-Command", "…"]`).
pub fn classify_argv(
    actions: Option<&Value>,
    argv: &[String],
    cwd: Option<&str>,
) -> Option<Classified> {
    classify(actions, &argv_to_script(argv), cwd)
}

fn summarize(stmts: Vec<Stmt>) -> Option<Classified> {
    let mut files: Vec<FileRead> = Vec::new();
    let (mut unknown, mut read, mut search) = (false, false, false);
    for s in stmts {
        match s {
            Stmt::Read(fs) => {
                read = true;
                for f in fs {
                    if !files.iter().any(|g| g.path == f.path) {
                        files.push(f);
                    }
                }
            }
            Stmt::List | Stmt::Search => search = true,
            Stmt::Neutral => {}
            Stmt::Unknown => unknown = true,
        }
    }
    let category = if unknown {
        "shell"
    } else if read {
        "read"
    } else if search {
        "search"
    } else {
        return None;
    };
    if category == "shell" && files.is_empty() {
        return None;
    }
    Some(Classified { category, files })
}

fn action_stmts(a: &Value, cwd: Option<&str>) -> Vec<Stmt> {
    let cmd = a
        .get("command")
        .or_else(|| a.get("cmd"))
        .and_then(Value::as_str)
        .unwrap_or("");
    let own = || script_stmts(cmd, cwd);
    match a.get("type").and_then(Value::as_str) {
        Some("read") => {
            // Codex's path is resolved against the command's folder; ours adds the range.
            let path = a
                .get("path")
                .and_then(Value::as_str)
                .or_else(|| a.get("name").and_then(Value::as_str))
                .filter(|p| !p.trim().is_empty());
            let mine = own();
            let range = match mine.as_slice() {
                [Stmt::Read(fs)] if fs.len() == 1 => (fs[0].offset, fs[0].limit),
                _ => (None, None),
            };
            match path {
                Some(p) => vec![Stmt::Read(vec![FileRead {
                    path: resolve(p, cwd),
                    offset: range.0,
                    limit: range.1,
                }])],
                None if !mine.is_empty() => mine,
                None => vec![Stmt::Unknown],
            }
        }
        Some("listFiles" | "list_files") => vec![Stmt::List],
        Some("search") => vec![Stmt::Search],
        _ => {
            let mine = own();
            if mine.is_empty() {
                vec![Stmt::Unknown]
            } else {
                mine
            }
        }
    }
}

/// `file:///C:/x` → `C:/x`, `file:///p` → `/p`; anything else unchanged.
pub fn file_url_to_path(s: &str) -> String {
    let Some(rest) = s.strip_prefix("file://") else {
        return s.to_string();
    };
    let decoded = percent_decode(rest);
    let b = decoded.as_bytes();
    if b.len() >= 3 && b[0] == b'/' && b[1].is_ascii_alphabetic() && b[2] == b':' {
        decoded[1..].to_string()
    } else {
        decoded
    }
}

fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() {
            if let Ok(v) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(b[i]);
        i += 1;
    }
    String::from_utf8(out).unwrap_or_else(|_| s.to_string())
}

fn is_absolute(p: &str) -> bool {
    let b = p.as_bytes();
    p.starts_with('/')
        || p.starts_with("\\\\")
        || p.starts_with('~')
        || (b.len() >= 3
            && b[0].is_ascii_alphabetic()
            && b[1] == b':'
            && matches!(b[2], b'/' | b'\\'))
}

/// A path as written, made absolute against `cwd` when it is relative.
pub fn resolve(path: &str, cwd: Option<&str>) -> String {
    let p = path.trim();
    if is_absolute(p) {
        return p.to_string();
    }
    let Some(cwd) = cwd.filter(|c| is_absolute(c)) else {
        return p.to_string();
    };
    let sep = if cwd.contains('\\') && !cwd.contains('/') {
        '\\'
    } else {
        '/'
    };
    let rel = p
        .strip_prefix("./")
        .or_else(|| p.strip_prefix(".\\"))
        .unwrap_or(p);
    if rel.is_empty() || rel == "." {
        return cwd.to_string();
    }
    format!("{}{sep}{rel}", cwd.trim_end_matches(['/', '\\']))
}

// ---------------------------------------------------------------------------------------------
// Words and statements
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
struct Word {
    /// The word without its quotes.
    text: String,
    /// Parts split at unquoted commas (a PowerShell list: `'a','b'`).
    parts: Vec<String>,
    /// Contains an unquoted (or double-quoted) `$`, `*`, `?`, backtick or `(`: not a literal path.
    dynamic: bool,
    quoted: bool,
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum Sep {
    Pipe,
    End,
}

#[derive(Debug, Clone, PartialEq)]
enum Tok {
    W(Word),
    S(Sep),
    /// A redirection (`>`, `2>&1`, …) and its target.
    Redirect(String),
}

fn tokenize(s: &str) -> Option<Vec<Tok>> {
    let chars: Vec<char> = s.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    let mut cur: Option<Word> = None;
    let mut part = String::new();
    fn flush(cur: &mut Option<Word>, part: &mut String, out: &mut Vec<Tok>) {
        if let Some(mut w) = cur.take() {
            w.parts.push(std::mem::take(part));
            w.parts.retain(|p| !p.is_empty());
            out.push(Tok::W(w));
        }
        part.clear();
    }
    let word = |cur: &mut Option<Word>| {
        cur.get_or_insert_with(|| Word {
            text: String::new(),
            parts: Vec::new(),
            dynamic: false,
            quoted: false,
        });
    };
    while i < chars.len() {
        let c = chars[i];
        match c {
            ' ' | '\t' | '\r' => {
                flush(&mut cur, &mut part, &mut out);
                i += 1;
            }
            '\n' | ';' => {
                flush(&mut cur, &mut part, &mut out);
                out.push(Tok::S(Sep::End));
                i += 1;
            }
            '&' if chars.get(i + 1) == Some(&'&') => {
                flush(&mut cur, &mut part, &mut out);
                out.push(Tok::S(Sep::End));
                i += 2;
            }
            '|' if chars.get(i + 1) == Some(&'|') => {
                flush(&mut cur, &mut part, &mut out);
                out.push(Tok::S(Sep::End));
                i += 2;
            }
            '|' => {
                flush(&mut cur, &mut part, &mut out);
                out.push(Tok::S(Sep::Pipe));
                i += 1;
            }
            '>' | '<' => {
                // `2>` / `2>&1`: the stream number is the word just read.
                let mut r = String::new();
                if let Some(w) = &cur {
                    if w.text.chars().all(|c| c.is_ascii_digit()) && !w.quoted {
                        r.push_str(&w.text);
                        cur = None;
                        part.clear();
                    }
                }
                flush(&mut cur, &mut part, &mut out);
                while i < chars.len() && matches!(chars[i], '>' | '<' | '&') {
                    r.push(chars[i]);
                    i += 1;
                }
                while i < chars.len() && chars[i] == ' ' {
                    i += 1;
                }
                let start = i;
                while i < chars.len() && !matches!(chars[i], ' ' | '\t' | '\n' | ';' | '|') {
                    i += 1;
                }
                r.extend(&chars[start..i]);
                out.push(Tok::Redirect(r));
            }
            '\'' => {
                word(&mut cur);
                let w = cur.as_mut().unwrap();
                w.quoted = true;
                i += 1;
                loop {
                    match chars.get(i) {
                        None => return None,
                        // PowerShell doubles a quote inside single quotes.
                        Some('\'') if chars.get(i + 1) == Some(&'\'') => {
                            w.text.push('\'');
                            part.push('\'');
                            i += 2;
                        }
                        Some('\'') => {
                            i += 1;
                            break;
                        }
                        Some(&ch) => {
                            w.text.push(ch);
                            part.push(ch);
                            i += 1;
                        }
                    }
                }
            }
            '"' => {
                word(&mut cur);
                let w = cur.as_mut().unwrap();
                w.quoted = true;
                i += 1;
                loop {
                    match chars.get(i) {
                        None => return None,
                        Some('\\') if chars.get(i + 1) == Some(&'"') => {
                            w.text.push('"');
                            part.push('"');
                            i += 2;
                        }
                        Some('`') => {
                            w.dynamic = true;
                            i += 1;
                        }
                        Some('"') => {
                            i += 1;
                            break;
                        }
                        Some(&ch) => {
                            if ch == '$' {
                                w.dynamic = true;
                            }
                            w.text.push(ch);
                            part.push(ch);
                            i += 1;
                        }
                    }
                }
            }
            ',' => {
                word(&mut cur);
                let w = cur.as_mut().unwrap();
                w.text.push(',');
                w.parts.push(std::mem::take(&mut part));
                i += 1;
            }
            _ => {
                word(&mut cur);
                let w = cur.as_mut().unwrap();
                if matches!(c, '$' | '*' | '?' | '`' | '(' | ')' | '{' | '}' | '[' | ']') {
                    w.dynamic = true;
                }
                w.text.push(c);
                part.push(c);
                i += 1;
            }
        }
    }
    flush(&mut cur, &mut part, &mut out);
    Some(out)
}

/// Statements of a script, each a pipeline of commands (words) with its redirections.
fn split(toks: Vec<Tok>) -> Vec<(Vec<Vec<Word>>, Vec<String>)> {
    let mut out = Vec::new();
    let mut stages: Vec<Vec<Word>> = vec![Vec::new()];
    let mut redirects = Vec::new();
    for t in toks {
        match t {
            Tok::W(w) => stages.last_mut().unwrap().push(w),
            Tok::Redirect(r) => redirects.push(r),
            Tok::S(Sep::Pipe) => stages.push(Vec::new()),
            Tok::S(Sep::End) => {
                if stages.iter().any(|s| !s.is_empty()) {
                    out.push((std::mem::take(&mut stages), std::mem::take(&mut redirects)));
                }
                stages = vec![Vec::new()];
                redirects.clear();
            }
        }
    }
    if stages.iter().any(|s| !s.is_empty()) {
        out.push((stages, redirects));
    }
    out
}

fn script_stmts(script: &str, cwd: Option<&str>) -> Vec<Stmt> {
    let script = script.trim();
    if script.is_empty() {
        return vec![];
    }
    // Here-strings / here-docs feed a program a script: never just a read.
    if script.contains("@'") || script.contains("@\"") || script.contains("<<") {
        return vec![Stmt::Unknown];
    }
    let Some(toks) = tokenize(script) else {
        return vec![Stmt::Unknown];
    };
    let mut cwd = cwd.map(String::from);
    let mut out = Vec::new();
    for (stages, redirects) in split(toks) {
        let harmless = redirects.iter().all(|r| {
            matches!(
                r.as_str(),
                "2>&1" | "2>/dev/null" | "2>$null" | "2>nul" | "2>NUL" | ">/dev/null" | ">$null"
            )
        });
        if !harmless {
            out.push(Stmt::Unknown);
            continue;
        }
        out.push(statement(&stages, &mut cwd));
    }
    out
}

fn program(w: &Word) -> String {
    let base = w.text.rsplit(['/', '\\']).next().unwrap_or(&w.text);
    let lower = base.to_ascii_lowercase();
    lower.strip_suffix(".exe").unwrap_or(&lower).to_string()
}

fn statement(stages: &[Vec<Word>], cwd: &mut Option<String>) -> Stmt {
    let Some(first) = stages.first().filter(|s| !s.is_empty()) else {
        return Stmt::Unknown;
    };
    if stages.len() == 1 {
        if let Some(s) = neutral(first, cwd) {
            return s;
        }
    }
    let prog = program(&first[0]);
    let args = &first[1..];
    let mut stmt = match prog.as_str() {
        // `type -a ls` (bash) describes a command.
        "type" if args.iter().any(|a| a.text.starts_with('-') && !a.quoted) => Stmt::Unknown,
        "cat" | "type" | "gc" | "get-content" | "bat" | "batcat" | "less" | "more" => {
            reader(args, cwd.as_deref())
        }
        "head" => head_tail(args, cwd.as_deref(), true),
        "tail" => head_tail(args, cwd.as_deref(), false),
        "nl" => nl(args, cwd.as_deref()),
        "sed" => sed(args, cwd.as_deref()),
        "ls" | "dir" | "gci" | "get-childitem" | "tree" | "fd" | "fdfind" | "eza" | "exa"
        | "get-item" | "gi" | "test-path" => Stmt::List,
        "find" => {
            if args.iter().any(|a| {
                matches!(
                    a.text.as_str(),
                    "-exec" | "-execdir" | "-delete" | "-ok" | "-okdir" | "-fprint" | "-fls"
                )
            }) {
                Stmt::Unknown
            } else {
                Stmt::List
            }
        }
        "rg" => {
            if args
                .iter()
                .any(|a| matches!(a.text.as_str(), "--replace" | "-r" | "--pre") && !a.quoted)
            {
                Stmt::Unknown
            } else if args.iter().any(|a| a.text == "--files") {
                Stmt::List
            } else {
                Stmt::Search
            }
        }
        "grep" | "egrep" | "fgrep" | "ag" | "ack" | "findstr" | "select-string" | "sls" => {
            Stmt::Search
        }
        "git" => match args.first().map(|a| a.text.as_str()) {
            Some("grep") => Stmt::Search,
            Some("ls-files") => Stmt::List,
            _ => Stmt::Unknown,
        },
        "wc" => Stmt::Search,
        _ => Stmt::Unknown,
    };
    for stage in &stages[1..] {
        if !filter(stage, &mut stmt) {
            return Stmt::Unknown;
        }
    }
    stmt
}

/// `cd x`, `$env:X = …`, `echo '---'` and the like.
fn neutral(words: &[Word], cwd: &mut Option<String>) -> Option<Stmt> {
    let prog = program(&words[0]);
    let first = words[0].text.as_str();
    match prog.as_str() {
        "cd" | "chdir" | "set-location" | "sl" | "pushd" => {
            let target = words[1..]
                .iter()
                .find(|w| !w.text.starts_with('-'))
                .filter(|w| !w.dynamic)?;
            let base = cwd.clone();
            *cwd = Some(resolve(&target.text, base.as_deref()));
            Some(Stmt::Neutral)
        }
        "echo" | "write-output" | "write-host" | "printf" | "pwd" | "get-location" | "popd"
        | "true" | "set" | "export" => Some(Stmt::Neutral),
        _ if first.starts_with("$env:")
            || first.starts_with("$OutputEncoding")
            || first.starts_with("[Console]::")
            || first.starts_with("$ErrorActionPreference")
            || first.starts_with("$ProgressPreference") =>
        {
            Some(Stmt::Neutral)
        }
        _ => None,
    }
}

fn literal_paths(w: &Word) -> Option<Vec<String>> {
    if w.dynamic || w.text.is_empty() {
        return None;
    }
    Some(
        w.parts
            .iter()
            .map(|p| p.trim().to_string())
            .filter(|p| !p.is_empty())
            .collect(),
    )
}

/// PowerShell parameters of Get-Content that take a value (lowercase, without the dash).
const PS_VALUE_PARAMS: &[&str] = &[
    "path",
    "literalpath",
    "lp",
    "pspath",
    "encoding",
    "totalcount",
    "head",
    "first",
    "tail",
    "last",
    "readcount",
    "delimiter",
    "stream",
    "filter",
    "include",
    "exclude",
    "credential",
];

fn reader(args: &[Word], cwd: Option<&str>) -> Stmt {
    let mut files = Vec::new();
    let mut limit = None;
    let mut i = 0;
    while i < args.len() {
        let a = &args[i];
        if a.text.starts_with('-') && !a.quoted && a.text.len() > 1 {
            let (name, inline) = match a.text[1..].split_once(':') {
                Some((n, v)) => (n.to_ascii_lowercase(), Some(v.to_string())),
                None => (a.text[1..].to_ascii_lowercase(), None),
            };
            if PS_VALUE_PARAMS.contains(&name.as_str()) {
                let value = match inline {
                    Some(v) => Some(v),
                    None => {
                        i += 1;
                        args.get(i).map(|w| w.text.clone())
                    }
                };
                match name.as_str() {
                    "path" | "literalpath" | "lp" | "pspath" => {
                        let paths = if a.text.contains(':') {
                            value.map(|v| vec![v]).filter(|_| !a.dynamic)
                        } else {
                            args.get(i).and_then(literal_paths)
                        };
                        match paths {
                            Some(ps) => files.extend(ps),
                            None => return Stmt::Unknown,
                        }
                    }
                    "totalcount" | "head" | "first" => {
                        limit = value.and_then(|v| v.parse::<u64>().ok());
                    }
                    "filter" | "include" | "exclude" | "stream" | "credential" => {
                        return Stmt::Unknown
                    }
                    _ => {}
                }
            }
            i += 1;
            continue;
        }
        match literal_paths(a) {
            Some(ps) => files.extend(ps),
            None => return Stmt::Unknown,
        }
        i += 1;
    }
    if files.is_empty() {
        return Stmt::Unknown;
    }
    let one = files.len() == 1;
    Stmt::Read(
        files
            .into_iter()
            .map(|p| FileRead {
                path: resolve(&p, cwd),
                offset: limit.filter(|_| one).map(|_| 1),
                limit: limit.filter(|_| one),
            })
            .collect(),
    )
}

fn count_arg(args: &[Word], i: &mut usize) -> Option<u64> {
    let a = &args[*i].text;
    if let Some(n) = a.strip_prefix("-n").or_else(|| a.strip_prefix("--lines=")) {
        if n.is_empty() {
            *i += 1;
            return args
                .get(*i)
                .and_then(|w| w.text.trim_start_matches('+').parse().ok());
        }
        return n.trim_start_matches('+').parse().ok();
    }
    a.strip_prefix('-').and_then(|n| n.parse().ok())
}

fn head_tail(args: &[Word], cwd: Option<&str>, head: bool) -> Stmt {
    let mut files = Vec::new();
    let mut n = None;
    let mut i = 0;
    while i < args.len() {
        let a = &args[i];
        if a.text.starts_with('-') && !a.quoted {
            if a.text == "-c" || a.text.starts_with("--bytes") || a.text == "-f" {
                if a.text == "-c" {
                    i += 1;
                }
            } else if let Some(v) = count_arg(args, &mut i) {
                n = Some(v);
            }
            i += 1;
            continue;
        }
        match literal_paths(a) {
            Some(ps) => files.extend(ps),
            None => return Stmt::Unknown,
        }
        i += 1;
    }
    if files.is_empty() {
        return Stmt::Unknown;
    }
    let one = files.len() == 1;
    Stmt::Read(
        files
            .into_iter()
            .map(|p| FileRead {
                path: resolve(&p, cwd),
                offset: n.filter(|_| head && one).map(|_| 1),
                limit: n.filter(|_| head && one),
            })
            .collect(),
    )
}

fn nl(args: &[Word], cwd: Option<&str>) -> Stmt {
    let mut files = Vec::new();
    let mut i = 0;
    while i < args.len() {
        let a = &args[i];
        if a.text.starts_with('-') && !a.quoted {
            // `-b a`, `-w 4`: a flag letter alone takes the next word.
            if a.text.len() == 2 && "bwsvinldfh".contains(&a.text[1..]) {
                i += 1;
            }
            i += 1;
            continue;
        }
        match literal_paths(a) {
            Some(ps) => files.extend(ps),
            None => return Stmt::Unknown,
        }
        i += 1;
    }
    if files.len() != 1 {
        return Stmt::Unknown;
    }
    Stmt::Read(vec![FileRead::new(resolve(&files[0], cwd))])
}

/// `A,Bp` / `Ap` / `A,$p` → (offset, limit).
fn sed_range(script: &str) -> Option<(u64, Option<u64>)> {
    let body = script.trim().strip_suffix('p')?;
    let (a, b) = match body.split_once(',') {
        Some((a, b)) => (a, Some(b)),
        None => (body, None),
    };
    let start: u64 = a.trim().parse().ok()?;
    match b.map(str::trim) {
        None => Some((start, Some(1))),
        Some("$") => Some((start, None)),
        Some(end) => {
            let end: u64 = end.parse().ok()?;
            (end >= start).then_some((start, Some(end - start + 1)))
        }
    }
}

fn sed(args: &[Word], cwd: Option<&str>) -> Stmt {
    let mut quiet = false;
    let mut range = None;
    let mut files = Vec::new();
    for a in args {
        match a.text.as_str() {
            "-n" | "--quiet" | "--silent" if !a.quoted => quiet = true,
            "-e" if !a.quoted => {}
            t if t.starts_with('-') && !a.quoted => return Stmt::Unknown, // -i edits in place
            t if range.is_none() => match sed_range(t) {
                Some(r) => range = Some(r),
                None => return Stmt::Unknown,
            },
            _ => match literal_paths(a) {
                Some(ps) => files.extend(ps),
                None => return Stmt::Unknown,
            },
        }
    }
    if !quiet || range.is_none() || files.len() != 1 {
        return Stmt::Unknown;
    }
    let (offset, limit) = range.unwrap();
    Stmt::Read(vec![FileRead {
        path: resolve(&files[0], cwd),
        offset: Some(offset),
        limit,
    }])
}

/// A later stage of a pipeline that only shapes the output. Narrows a single read's range.
fn filter(stage: &[Word], stmt: &mut Stmt) -> bool {
    let Some(first) = stage.first() else {
        return false;
    };
    let args = &stage[1..];
    let mut set_range = |offset: u64, limit: Option<u64>| {
        if let Stmt::Read(fs) = stmt {
            if let [f] = fs.as_mut_slice() {
                if f.offset.is_none() && f.limit.is_none() {
                    f.offset = Some(offset);
                    f.limit = limit;
                }
            }
        }
    };
    match program(first).as_str() {
        "head" => {
            let mut n = None;
            let mut i = 0;
            while i < args.len() {
                if args[i].text.starts_with('-') {
                    n = count_arg(args, &mut i).or(n);
                } else {
                    return false;
                }
                i += 1;
            }
            set_range(1, Some(n.unwrap_or(10)));
            true
        }
        "sed" => {
            let mut range = None;
            let mut quiet = false;
            for a in args {
                match a.text.as_str() {
                    "-n" => quiet = true,
                    t => match sed_range(t) {
                        Some(r) if range.is_none() => range = Some(r),
                        _ => return false,
                    },
                }
            }
            match (quiet, range) {
                (true, Some((o, l))) => {
                    set_range(o, l);
                    true
                }
                _ => false,
            }
        }
        "select-object" | "select" => {
            let (mut skip, mut first_n) = (None, None);
            let mut i = 0;
            while i < args.len() {
                let w = &args[i];
                if !w.text.starts_with('-') || w.quoted {
                    i += 1; // the properties to keep
                    continue;
                }
                let value = || args.get(i + 1).and_then(|v| v.text.parse::<u64>().ok());
                match w.text[1..].to_ascii_lowercase().as_str() {
                    "skip" => skip = value(),
                    "first" => first_n = value(),
                    "last" | "index" | "skiplast" | "expandproperty" | "property" => {}
                    "unique" | "wait" => {
                        i += 1;
                        continue;
                    }
                    _ => return false,
                }
                i += 2;
            }
            if skip.is_some() || first_n.is_some() {
                set_range(skip.unwrap_or(0) + 1, first_n);
            }
            true
        }
        "tail" | "nl" | "wc" | "sort" | "uniq" | "cut" | "cat" | "less" | "more" | "grep"
        | "rg" | "findstr" | "select-string" | "sls" | "out-string" | "format-table" | "ft"
        | "format-list" | "fl" | "measure-object" | "measure" | "sort-object" | "out-host"
        | "column" | "tr" | "awk" => {
            // awk / tr only print here: anything that writes has a redirection, refused earlier.
            !(program(first) == "awk" && args.iter().any(|a| a.text.contains("system")))
        }
        _ => false,
    }
}

// ---------------------------------------------------------------------------------------------
// Unwrapping shells
// ---------------------------------------------------------------------------------------------

fn quote(w: &str) -> String {
    if w.is_empty() || w.contains([' ', '\t', '"', '\'', ';', '|', '&']) {
        format!("'{}'", w.replace('\'', "''"))
    } else {
        w.to_string()
    }
}

/// The script an argv runs (`bash -lc X` → `X`, `pwsh -Command X` → `X`), else the argv joined.
pub fn argv_to_script(argv: &[String]) -> String {
    if let Some(first) = argv.first() {
        let p = {
            let base = first.rsplit(['/', '\\']).next().unwrap_or(first);
            let l = base.to_ascii_lowercase();
            l.strip_suffix(".exe").unwrap_or(&l).to_string()
        };
        match p.as_str() {
            "bash" | "sh" | "zsh" | "dash" => {
                if let Some(i) = argv
                    .iter()
                    .position(|a| matches!(a.as_str(), "-c" | "-lc" | "-ic" | "-lic"))
                {
                    if let Some(s) = argv.get(i + 1) {
                        return s.clone();
                    }
                }
            }
            "pwsh" | "powershell" => {
                if let Some(i) = argv.iter().position(|a| {
                    let l = a.to_ascii_lowercase();
                    l == "-command" || l == "-c"
                }) {
                    return argv[i + 1..].join(" ");
                }
            }
            "cmd" => {
                if let Some(i) = argv.iter().position(|a| a.eq_ignore_ascii_case("/c")) {
                    return argv[i + 1..].join(" ");
                }
            }
            _ => {}
        }
    }
    argv.iter().map(|a| quote(a)).collect::<Vec<_>>().join(" ")
}

/// A command line that may start with a shell (`/bin/zsh -lc '…'`, `"…\pwsh.exe" -Command …`).
pub fn unwrap_shell(command: &str) -> String {
    let Some(toks) = tokenize(command) else {
        return command.to_string();
    };
    let mut argv = Vec::new();
    for t in &toks {
        match t {
            Tok::W(w) => argv.push(w.text.clone()),
            _ => return command.to_string(),
        }
    }
    let p = argv
        .first()
        .map(|f| {
            let base = f.rsplit(['/', '\\']).next().unwrap_or(f);
            base.to_ascii_lowercase()
        })
        .unwrap_or_default();
    let p = p.strip_suffix(".exe").unwrap_or(&p);
    if matches!(
        p,
        "bash" | "sh" | "zsh" | "dash" | "pwsh" | "powershell" | "cmd"
    ) {
        let s = argv_to_script(&argv);
        if s != argv.iter().map(|a| quote(a)).collect::<Vec<_>>().join(" ") {
            return s;
        }
    }
    command.to_string()
}

/// Shell commands a code-mode `exec` script runs (`tools.exec_command({cmd: "…"})`), with their
/// `workdir` when given. None when the script calls any other tool.
pub fn exec_script_commands(script: &str) -> Option<Vec<(String, Option<String>)>> {
    let mut out = Vec::new();
    let mut rest = script;
    while let Some(i) = rest.find("tools.") {
        let after = &rest[i + "tools.".len()..];
        let name: String = after
            .chars()
            .take_while(|c| c.is_ascii_alphanumeric() || *c == '_')
            .collect();
        if name != "exec_command" {
            return None;
        }
        let call = &after[name.len()..];
        let end = call.find("})").map(|e| e + 2).unwrap_or(call.len());
        let body = &call[..end];
        let cmd = js_field(body, "cmd")?;
        out.push((cmd, js_field(body, "workdir")));
        rest = &call[end..];
    }
    (!out.is_empty()).then_some(out)
}

/// The string literal after `key:` (or `"key":`) in a JS object literal.
fn js_field(body: &str, key: &str) -> Option<String> {
    let mut search = body;
    loop {
        let i = search.find(key)?;
        let before = search[..i].chars().last();
        let after = search[i + key.len()..].trim_start();
        let after = after
            .strip_prefix(['"', '\''])
            .unwrap_or(after)
            .trim_start();
        let ok_before = matches!(before, None | Some('{' | ',' | ' ' | '"' | '\'' | '\n'));
        if let (true, Some(v)) = (ok_before, after.strip_prefix(':')) {
            return js_string(v.trim_start());
        }
        search = &search[i + key.len()..];
    }
}

fn js_string(s: &str) -> Option<String> {
    let mut it = s.chars();
    let q = it.next().filter(|c| matches!(c, '"' | '\'' | '`'))?;
    let mut out = String::new();
    while let Some(c) = it.next() {
        match c {
            '\\' => match it.next()? {
                'n' => out.push('\n'),
                't' => out.push('\t'),
                'r' => out.push('\r'),
                'u' => {
                    let hex: String = it.by_ref().take(4).collect();
                    out.push(char::from_u32(u32::from_str_radix(&hex, 16).ok()?)?);
                }
                other => out.push(other),
            },
            '$' if q == '`' && it.clone().next() == Some('{') => return None,
            c if c == q => return Some(out),
            c => out.push(c),
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cls(cmd: &str, cwd: Option<&str>) -> Option<Classified> {
        classify(None, cmd, cwd)
    }

    fn read(path: &str, offset: Option<u64>, limit: Option<u64>) -> FileRead {
        FileRead {
            path: path.into(),
            offset,
            limit,
        }
    }

    #[test]
    fn posix_reads_with_ranges() {
        let c = cls("sed -n '120,169p' src/main.rs", Some("/repo")).unwrap();
        assert_eq!(c.category, "read");
        assert_eq!(
            c.files,
            vec![read("/repo/src/main.rs", Some(120), Some(50))]
        );
        let c = cls("cat README.md", Some("/repo")).unwrap();
        assert_eq!(c.files, vec![read("/repo/README.md", None, None)]);
        let c = cls("nl -ba src/a.rs | sed -n '1,80p'", Some("/r")).unwrap();
        assert_eq!(c.files, vec![read("/r/src/a.rs", Some(1), Some(80))]);
        let c = cls("head -n 40 /etc/hosts", None).unwrap();
        assert_eq!(c.files, vec![read("/etc/hosts", Some(1), Some(40))]);
        let c = cls("cat a.txt b.txt", None).unwrap();
        assert_eq!(c.files.len(), 2);
        assert_eq!(c.read_title().as_deref(), Some("a.txt, b.txt"));
        let c = cls("cd sub && cat x.rs", Some("/r")).unwrap();
        assert_eq!(c.files[0].path, "/r/sub/x.rs");
    }

    #[test]
    fn powershell_reads() {
        let c = cls(
            "Get-Content -Raw -LiteralPath 'C:\\Users\\me\\a b\\SKILL.md'",
            None,
        )
        .unwrap();
        assert_eq!(c.category, "read");
        assert_eq!(c.files[0].path, "C:\\Users\\me\\a b\\SKILL.md");
        let c = cls(
            "Get-Content -Encoding UTF8 android/app/Page.kt | Select-Object -Skip 172 -First 56",
            Some("D:\\develop\\repo"),
        )
        .unwrap();
        assert_eq!(
            c.files,
            vec![read(
                "D:\\develop\\repo\\android/app/Page.kt",
                Some(173),
                Some(56)
            )]
        );
        let c = cls(
            "Get-Content -LiteralPath '.\\a.md','.\\b.md' -Encoding UTF8",
            Some("C:/w"),
        )
        .unwrap();
        assert_eq!(
            c.files.iter().map(|f| f.path.as_str()).collect::<Vec<_>>(),
            vec!["C:/w/a.md", "C:/w/b.md"]
        );
        let c = cls("Get-Content -LiteralPath x.log -TotalCount 25", Some("/w")).unwrap();
        assert_eq!(c.files, vec![read("/w/x.log", Some(1), Some(25))]);
        let c = cls(
            "$env:PYTHONIOENCODING='utf-8'; Get-Content notes.txt; Get-ChildItem -Name work",
            Some("/w"),
        )
        .unwrap();
        assert_eq!(c.category, "read");
        assert_eq!(c.files, vec![read("/w/notes.txt", None, None)]);
    }

    #[test]
    fn listing_searching_and_mixed() {
        assert_eq!(cls("rg -n 'fn main' src", None).unwrap().category, "search");
        assert_eq!(cls("rg --files", None).unwrap().category, "search");
        assert_eq!(
            cls(
                "Get-ChildItem -LiteralPath .\\work -File | Select-Object Name,Length",
                None
            )
            .unwrap()
            .category,
            "search"
        );
        assert_eq!(cls("ls -la", None).unwrap().category, "search");
        // A read next to something else: still a shell step, the file is still offered.
        let c = cls("cat Cargo.toml; cargo build", Some("/r")).unwrap();
        assert_eq!(c.category, "shell");
        assert_eq!(c.files[0].path, "/r/Cargo.toml");
        assert!(cls("cargo test", None).is_none());
        assert!(cls("git status", None).is_none());
    }

    #[test]
    fn writes_and_scripts_are_never_reads() {
        assert!(cls("cat a > b", None).is_none());
        assert!(cls("sed -i 's/a/b/' f.txt", None).is_none());
        assert!(cls("sed 's/a/b/' f.txt", None).is_none());
        assert!(cls("cat <<EOF > x\nhi\nEOF", None).is_none());
        assert!(cls("@'\nprint(1)\n'@ | python -", None).is_none());
        assert!(cls("find . -name '*.tmp' -delete", None).is_none());
        assert!(cls("cat $FILE", None).is_none());
        assert!(cls("Get-Content (Join-Path $a 'x')", None).is_none());
        assert!(cls("cat a.txt | xargs rm", None).is_none());
        // Harmless redirections are fine.
        assert_eq!(cls("cat a.txt 2>/dev/null", None).unwrap().category, "read");
    }

    #[test]
    fn shells_are_unwrapped() {
        let c = cls("/bin/zsh -lc \"sed -n '1,80p' README.md\"", Some("/p")).unwrap();
        assert_eq!(c.files, vec![read("/p/README.md", Some(1), Some(80))]);
        let c = cls(
            "\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\" -Command 'Get-Content -LiteralPath a.txt'",
            Some("C:\\w"),
        )
        .unwrap();
        assert_eq!(c.files[0].path, "C:\\w\\a.txt");
        let argv: Vec<String> = ["bash", "-lc", "cat x"]
            .iter()
            .map(|s| s.to_string())
            .collect();
        assert_eq!(
            classify_argv(None, &argv, Some("/q")).unwrap().files[0].path,
            "/q/x"
        );
    }

    #[test]
    fn codex_actions_in_both_spellings() {
        // app-server v2 `commandActions`.
        let actions = json!([{ "type": "read", "command": "sed -n '5,9p' a.rs", "name": "a.rs", "path": "/r/a.rs" }]);
        let c = classify(
            Some(&actions),
            "/bin/zsh -lc \"sed -n '5,9p' a.rs\"",
            Some("/r"),
        )
        .unwrap();
        assert_eq!(c.category, "read");
        assert_eq!(c.files, vec![read("/r/a.rs", Some(5), Some(5))]);
        let actions = json!([{ "type": "listFiles", "command": "ls", "path": null }]);
        assert_eq!(
            classify(Some(&actions), "ls", None).unwrap().category,
            "search"
        );
        // Rollout `parsed_cmd`: Codex leaves most PowerShell `unknown`; parsed again here.
        let parsed = json!([{ "type": "unknown", "cmd": "Get-Content -LiteralPath .\\w\\c.py" }]);
        let c = classify(Some(&parsed), "", Some("file:///C:/Users/me/proj")).unwrap();
        assert_eq!(c.files[0].path, "C:/Users/me/proj/w\\c.py");
        let parsed = json!([{ "type": "list_files", "cmd": "rg --files" }, { "type": "unknown", "cmd": "cargo test" }]);
        assert!(classify(Some(&parsed), "", None).is_none());
    }

    #[test]
    fn code_mode_exec_scripts() {
        let script = r#"text(await tools.exec_command({cmd:"Get-Content -LiteralPath 'C:\\a\\b.md'", workdir: "C:\\a"}))"#;
        let cmds = exec_script_commands(script).unwrap();
        assert_eq!(cmds[0].0, "Get-Content -LiteralPath 'C:\\a\\b.md'");
        assert_eq!(cmds[0].1.as_deref(), Some("C:\\a"));
        let two = r#"const a = await tools.exec_command({"cmd": "cat x"}); text(await tools.exec_command({cmd: 'ls'}))"#;
        assert_eq!(exec_script_commands(two).unwrap().len(), 2);
        assert!(exec_script_commands("await tools.apply_patch({input: 'x'})").is_none());
    }

    #[test]
    fn file_urls() {
        assert_eq!(file_url_to_path("file:///C:/Users/a%20b"), "C:/Users/a b");
        assert_eq!(file_url_to_path("file:///p"), "/p");
        assert_eq!(file_url_to_path("/x"), "/x");
    }
}
