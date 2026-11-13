//! Screen-state serialization for the terminal stream protocol.

use std::num::NonZeroU16;

use serde::Serialize;

/// Captures OSC window-title updates (vt100 reports them via callbacks).
#[derive(Debug, Default)]
pub struct TermCallbacks {
    pub title: String,
}

impl vt100::Callbacks for TermCallbacks {
    fn set_window_title(&mut self, _: &mut vt100::Screen, title: &[u8]) {
        self.title = String::from_utf8_lossy(title).chars().take(200).collect();
    }
}

pub type VtParser = vt100::Parser<TermCallbacks>;

pub fn nz(v: u16) -> NonZeroU16 {
    NonZeroU16::new(v.max(1)).expect("non-zero")
}

pub fn new_parser(rows: u16, cols: u16, scrollback: usize) -> VtParser {
    vt100::Parser::new_with_callbacks(nz(rows), nz(cols), scrollback, TermCallbacks::default())
}

/// (rows, cols) as plain integers.
pub fn size_of(screen: &vt100::Screen) -> (u16, u16) {
    let (r, c) = screen.size();
    (r.get(), c.get())
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq, Hash)]
pub struct Run {
    pub t: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fg: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bg: Option<String>,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub b: bool,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub i: bool,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub u: bool,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub inv: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq, Hash, Default)]
pub struct Line {
    pub runs: Vec<Run>,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub wrapped: bool,
}

impl Line {
    pub fn text(&self) -> String {
        self.runs.iter().map(|r| r.t.as_str()).collect()
    }
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Cursor {
    pub x: u16,
    pub y: u16,
    pub visible: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScreenSnapshot {
    pub version: u64,
    pub cols: u16,
    pub rows: u16,
    pub cursor: Cursor,
    pub lines: Vec<Line>,
    pub title: String,
    pub alt_screen: bool,
    pub application_cursor: bool,
    pub bracketed_paste: bool,
    pub exited: bool,
    pub exit_code: Option<i32>,
}

/// Colors are sent as either a palette index ("0".."255") or "#rrggbb".
fn color(c: vt100::Color) -> Option<String> {
    match c {
        vt100::Color::Default => None,
        vt100::Color::Idx(i) => Some(i.to_string()),
        vt100::Color::Rgb(r, g, b) => Some(format!("#{r:02x}{g:02x}{b:02x}")),
    }
}

fn row_line(screen: &vt100::Screen, row: u16, cols: u16) -> Line {
    let mut runs: Vec<Run> = Vec::new();
    for col in 0..cols {
        let Some(cell) = screen.cell(row, col) else {
            continue;
        };
        if cell.is_wide_continuation() {
            continue;
        }
        let mut text = cell.contents().to_string();
        if text.is_empty() {
            text.push(' ');
        }
        let attrs = Run {
            t: String::new(),
            fg: color(cell.fgcolor()),
            bg: color(cell.bgcolor()),
            b: cell.bold(),
            i: cell.italic(),
            u: cell.underline(),
            inv: cell.inverse(),
        };
        match runs.last_mut() {
            Some(last)
                if last.fg == attrs.fg
                    && last.bg == attrs.bg
                    && last.b == attrs.b
                    && last.i == attrs.i
                    && last.u == attrs.u
                    && last.inv == attrs.inv =>
            {
                last.t.push_str(&text);
            }
            _ => runs.push(Run { t: text, ..attrs }),
        }
    }
    // Trim trailing default-styled spaces to keep frames small.
    if let Some(last) = runs.last_mut() {
        if last.fg.is_none() && last.bg.is_none() && !last.inv && !last.u {
            let trimmed = last.t.trim_end_matches(' ').len();
            last.t.truncate(trimmed);
        }
    }
    if runs.last().is_some_and(|r| r.t.is_empty()) {
        runs.pop();
    }
    Line {
        runs,
        wrapped: screen.row_wrapped(row),
    }
}

pub fn snapshot(
    parser: &VtParser,
    version: u64,
    exited: bool,
    exit_code: Option<i32>,
) -> ScreenSnapshot {
    let screen = parser.screen();
    let (rows, cols) = size_of(screen);
    let (cy, cx) = screen.cursor_position();
    ScreenSnapshot {
        version,
        cols,
        rows,
        cursor: Cursor {
            x: cx,
            y: cy,
            visible: !screen.hide_cursor(),
        },
        lines: (0..rows).map(|r| row_line(screen, r, cols)).collect(),
        title: parser.callbacks().title.clone(),
        alt_screen: screen.alternate_screen(),
        application_cursor: screen.application_cursor(),
        bracketed_paste: screen.bracketed_paste(),
        exited,
        exit_code,
    }
}

/// Read `limit` lines of scrollback ending `offset` lines above the visible
/// screen top. Returns (lines oldest-first, total scrollback lines available).
pub fn scrollback(parser: &mut VtParser, offset: usize, limit: usize) -> (Vec<Line>, usize) {
    let total = parser.screen().scrollback_len();
    let (rows, cols) = size_of(parser.screen());
    let limit = limit.min(500);
    let mut lines = Vec::new();
    if total > 0 && offset < total {
        // Showing scrollback position `s` makes screen row 0 = scrollback line
        // (total - s). We want lines [start, end) counted from the oldest.
        let end = total - offset; // exclusive, oldest-based index
        let start = end.saturating_sub(limit);
        let mut idx = start;
        while idx < end {
            let s = total - idx; // scroll so that row 0 is line `idx`
            parser.screen_mut().set_scrollback(s);
            let visible = (end - idx).min(rows as usize);
            for r in 0..visible {
                lines.push(row_line(parser.screen(), r as u16, cols));
            }
            idx += visible;
        }
    }
    parser.screen_mut().set_scrollback(0);
    (lines, total)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colors_and_runs() {
        let mut p = new_parser(5, 20, 100);
        p.process(b"\x1b[31mred\x1b[0m plain \x1b[1;38;2;1;2;3mx");
        let s = snapshot(&p, 1, false, None);
        let l = &s.lines[0];
        assert_eq!(l.runs[0].t, "red");
        assert_eq!(l.runs[0].fg.as_deref(), Some("1"));
        assert_eq!(l.runs[1].t, " plain ");
        assert_eq!(l.runs[2].fg.as_deref(), Some("#010203"));
        assert!(l.runs[2].b);
        assert_eq!(l.text(), "red plain x");
    }

    #[test]
    fn unicode_wide_chars() {
        let mut p = new_parser(3, 10, 0);
        p.process("中文ok".as_bytes());
        let s = snapshot(&p, 1, false, None);
        assert_eq!(s.lines[0].text(), "中文ok");
    }

    #[test]
    fn scrollback_pages() {
        let mut p = new_parser(3, 10, 100);
        for i in 0..10 {
            p.process(format!("l{i}\r\n").as_bytes());
        }
        // screen shows l8, l9, "" ; scrollback holds l0..l7
        let (lines, total) = scrollback(&mut p, 0, 100);
        assert_eq!(total, 8);
        assert_eq!(
            lines.iter().map(|l| l.text()).collect::<Vec<_>>(),
            vec!["l0", "l1", "l2", "l3", "l4", "l5", "l6", "l7"]
        );
        let (lines, _) = scrollback(&mut p, 2, 3);
        assert_eq!(
            lines.iter().map(|l| l.text()).collect::<Vec<_>>(),
            vec!["l3", "l4", "l5"]
        );
        // visible screen restored
        assert_eq!(p.screen().scrollback(), 0);
    }
}
