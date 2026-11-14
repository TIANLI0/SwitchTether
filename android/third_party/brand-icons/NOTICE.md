# Agent brand marks

| Drawable | Source | Version | License |
| :--- | :--- | :--- | :--- |
| `app/src/main/res/drawable/ic_agent_claude.xml` | LobeHub Icons, `icons/claude.svg` | `@lobehub/icons-static-svg` 1.102.0 | MIT |
| `app/src/main/res/drawable/ic_agent_openai.xml` | LobeHub Icons, `icons/openai.svg` | `@lobehub/icons-static-svg` 1.102.0 | MIT |

- Upstream: https://github.com/lobehub/lobe-icons (npm `@lobehub/icons-static-svg`, tarball
  sha256 `ad3f2ab605d59f3968b9ea57c9b2aab941ee3608da72fca57ac6e0ded301f5a2`).
- Conversion: the single `<path>` of each 24x24 SVG was copied verbatim into an Android
  VectorDrawable (`fill-rule="evenodd"` → `android:fillType="evenOdd"`); no geometry was edited.
  Claude is filled with its brand colour `#D97757`; the OpenAI mark is monochrome and tinted at
  runtime (`COUITheme.colorScheme.onSurface`) so it follows light/dark mode.
- The marks identify which agent a session belongs to (nominative use). "Claude" is a trademark of
  Anthropic PBC and "OpenAI" a trademark of OpenAI; SwitchTether is not affiliated with either.

## MIT License (LobeHub Icons)

Copyright (c) 2023 LobeHub

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
