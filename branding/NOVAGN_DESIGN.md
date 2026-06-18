# NovaGN Design Language

NovaGN is a premium, minimal, feature-forward game launcher for Android handhelds. It runs PC
games via Wine/Box64 and is built on GameNative (credited as the foundation). This document is
the single source of truth for the app's distinct visual identity — implementers follow it so the
UI reads as its OWN product, not recolored GameNative.

## Identity in one line
Near-black canvas, ONE electric-indigo accent, generous space, and the app's power features
(speed-hack, cheats, auto-tuner, crash-fixes) surfaced as first-class identity — not buried.

## Colors
- Ink (background):        #0B0B0F
- Panel surface:           #13131B   (cards/panels lift slightly above ink)
- Panel elevated:          #16161F   (focused card bg) / #1A1A24
- Divider / hairline:      #1A1A24 / #262633
- Text primary:            #FAFAFA
- Text secondary:          #C0C0CC
- Text muted / labels:     #9CA0AD (muted) / #555 (section labels)
- Accent (THE color):      #6D5BF6   indigo
- Accent bright (text/readout on dark, focus tint): #8B7BFF / #9B8FFA
- Accent deep (pressed / gradient end): #4B3CD0
Chip semantics:
- Power features (speed-hack, cheats):  indigo  #6D5BF6 @ ~18% fill + #9B8FFA text
- Working / auto-tuned:                 green   #1A2A1A fill + #5CB85C text
- Needs attention / fix available:      amber   #2A1A08 fill + #E29060 text
- Neutral info (source, date):          no fill, #9CA0AD text only

## Type (Bricolage Grotesque)
- Screen title / game name: 28sp / 700 / line-height 1.1
- Section label:            11sp / 600 / +0.08em / UPPERCASE / #555
- Body / metadata:          12sp / 400 / #C0C0CC
- Chip / badge:             10-11sp / 500
- Slider value readout:     16sp / 700 / #9B8FFA
Weights used: 400, 600, 700 only. Avoid 500 (ambiguous).

## Spacing (8dp grid)
- Card internal padding: 8dp
- Grid card gap: 10dp
- Panel section padding: 12dp vertical / 14-16dp horizontal
- Between major zones (rail | grid | panel): 0dp gap + 1px #1A1A24 divider

## Radii
- Game cards: 10dp
- Buttons / primary actions: 8dp
- Chips / badges: 4dp
- Panels / drawers: 12dp on exposed corners, 0dp on the screen-edge side
- No fully-rounded pills for interactive controls (pills = tags, not buttons)

## Accent rules
Permitted: active nav item, focused card border, Launch button, active chip, slider filled
track, toggle ON, the quick-menu "live" dot. NOT permitted: section labels, decorative borders,
or body text (use the bright tint #9B8FFA for accent-colored text/readouts).
ONE glow in the whole app: focused card → 2px #6D5BF6 border + 0 0 16px #6D5BF640.

## Focus / selection (gamepad-first)
- Unfocused card: #13131B bg, #1E1E2A 1dp border.
- Focused card: scale 1.05x (120ms EaseOutCubic), 2dp #6D5BF6 border, bg #16161F, the one glow.
- Focused controls: thumb/handle indigo fill + 2dp indigo ring. Never an outline-only ring.

## Motion
- Card focus scale: 120ms EaseOutCubic
- Panel slide-in: 200ms EaseOutQuart; slide-out: 150ms EaseInQuart (snappier dismiss)
- Filter chip switch: 80ms opacity crossfade
- No spring physics on navigation (springs read playful; NovaGN is premium/minimal).
- No idle/continuous animations in the library.

## Screen layouts (the distinct structure)

### Library — left rail + feature-tagged grid
- Persistent 64dp icon-only LEFT RAIL: Library, Favorites, Recent, Settings. Active = indigo fill.
  (Replaces GameNative's full-width TOP TAB strip — the #1 generic tell.)
- Top of content: compact CONTINUE-PLAYING strip (last game thumb + speed/cheat badges + Resume),
  NOT a big hero carousel.
- 4-column cover grid (2:3 art, ~160dp). Title + FEATURE-TAG CHIPS in a footer strip below the
  art (not overlaid). Chips: "Speed Nx", "Auto-tuned", "Fix available" — the signature feature.
- Shoulder buttons cycle filter chips (All / Recent / genre) without leaving the grid.

### Game detail — art canvas + action dashboard
- Split: left ~2/3 = game art as atmospheric canvas (NO controls, gradient vignette into panel).
  Right ~1/3 = scrollable ACTION DASHBOARD: Launch (focus default) → Make It Work (with status)
  → Speed-hack slider+presets → Cheats toggles → Crash-fix card. Power features are PEERS of
  Launch, never a buried settings icon.

### Quick menu — right slide-in panel
- ~280dp right-side panel, slides in (200ms), game stays visible on the left under an 88% scrim.
- Sections (D-pad vertical): speed slider + presets → cheats (last-3 + more) → session stats
  (incl. speed-hack time gain) → settings/quit. Remembers scroll per game. Not radial, not bottom sheet.

## The 4 things that make NovaGN its own product
1. Feature-tag chips on library cards (the library is ALIVE — it shows work done per game).
2. Action dashboard on detail (Make-It-Work/Speed/Cheats as peers of Launch, not sub-menus).
3. Left icon-rail nav (gamepad-native, distinct from top tabs / bottom bars).
4. Speed-hack as persistent visible context (multiplier shown on cards, continue strip, quick menu).
