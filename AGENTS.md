# AI Agent Guidelines & Design System Contract

## Material Design 3 / M3 Expressive & Awesome Design Skills

The Material 3 Expressive specification lives at `.agents/skills/m3-expressive/`.
Awesome design system skills live at `.agents/skills/`.

Before writing or reviewing any Material Design UI (components, dp values, corner radii,
motion springs, type styles, color roles, adaptive layouts), consult
`.agents/skills/m3-expressive/SKILL.md` and follow its "Where to look" table into the
specific reference file.

### Key Rules & Numeric Bedrock:
- **10-Step Corner Radius Scale**:
  - `CornerLargeIncreased`: **20dp** (Cards, Hero FAB, Containment groups)
  - `CornerExtraLargeTop`: **28dp** (Bottom sheets, drawers)
  - `CornerFull`: Pill buttons, filter chips, search bar
  - **Optical roundness formula**: `inner radius = outer radius − padding`
- **Typography Scale (Emphasized M3 Expressive)**:
  - Display/Headline: Medium weight
  - `title-medium-emphasized`: 16sp / 24sp line height / Bold
  - `label-large-emphasized`: 14sp / 20sp line height / Bold (Primary buttons, FABs)
  - `body-medium`: 14sp / 20sp line height / Regular
- **Motion Springs (Expressive Scheme)**:
  - `Fast spatial`: Damping 0.6, Stiffness 800 (for touch interactions, card press, FAB)
  - `Default spatial`: Damping 0.8, Stiffness 380 (for sheets, dialogs, cards expand)
  - `Fast effects`: Damping 1.0, Stiffness 3800 (for color & opacity)
- **Hierarchy & Containment**:
  - Surface hierarchy: `surface` -> `surfaceContainerLow` (cards) -> `surfaceContainer` (docks) -> `surfaceContainerHigh` (search/dialogs).
  - Hero moments: 1-2 per screen (FAB, Primary Done pill).
