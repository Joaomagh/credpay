---
name: CredPay Console System
colors:
  surface: '#f8f9ff'
  surface-dim: '#cbdbf5'
  surface-bright: '#f8f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#eff4ff'
  surface-container: '#e5eeff'
  surface-container-high: '#dce9ff'
  surface-container-highest: '#d3e4fe'
  on-surface: '#0b1c30'
  on-surface-variant: '#3d4947'
  inverse-surface: '#213145'
  inverse-on-surface: '#eaf1ff'
  outline: '#6d7a77'
  outline-variant: '#bcc9c6'
  surface-tint: '#006a61'
  primary: '#00685f'
  on-primary: '#ffffff'
  primary-container: '#008378'
  on-primary-container: '#f4fffc'
  inverse-primary: '#6bd8cb'
  secondary: '#565e74'
  on-secondary: '#ffffff'
  secondary-container: '#dae2fd'
  on-secondary-container: '#5c647a'
  tertiary: '#006194'
  on-tertiary: '#ffffff'
  tertiary-container: '#007bb9'
  on-tertiary-container: '#fdfcff'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#89f5e7'
  primary-fixed-dim: '#6bd8cb'
  on-primary-fixed: '#00201d'
  on-primary-fixed-variant: '#005049'
  secondary-fixed: '#dae2fd'
  secondary-fixed-dim: '#bec6e0'
  on-secondary-fixed: '#131b2e'
  on-secondary-fixed-variant: '#3f465c'
  tertiary-fixed: '#cce5ff'
  tertiary-fixed-dim: '#93ccff'
  on-tertiary-fixed: '#001d31'
  on-tertiary-fixed-variant: '#004b73'
  background: '#f8f9ff'
  on-background: '#0b1c30'
  surface-variant: '#d3e4fe'
typography:
  display-lg:
    fontFamily: Inter
    fontSize: 36px
    fontWeight: '700'
    lineHeight: 44px
  display-lg-mobile:
    fontFamily: Inter
    fontSize: 28px
    fontWeight: '700'
    lineHeight: 36px
  headline-lg:
    fontFamily: Inter
    fontSize: 30px
    fontWeight: '600'
    lineHeight: 38px
  headline-lg-mobile:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
  headline-md:
    fontFamily: Inter
    fontSize: 22px
    fontWeight: '600'
    lineHeight: 28px
  headline-sm:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-lg:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 14px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1.5rem
  gutter-mobile: 1rem
  margin: 2rem
  margin-mobile: 1rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
---

## Brand & Style

This design system defines an institutional, mission-critical asynchronous financial processing console tailored for BRL operations. The visual identity bridges enterprise fintech rigour with an inviting, modern digital workplace aesthetic.

### Brand Personality & Emotional Objectives
- **Authoritative & Resilient:** Communicates structural stability, precise ledger handling, and instant operational visibility.
- **Warm & Approachable:** Eliminates the visual stress and clinical coldness typical of legacy banking mainframes through gentle radii, soft contrast backgrounds, and breathing room.
- **Predictable & Transparent:** Reduces cognitive friction for financial analysts monitoring long-running queues, batch payouts, and stateful transactions.

### Design Archetype: Modern Corporate Precision
The design draws from modern corporate SaaS minimalism combined with low-contrast structural outlines and subtle ambient depth. High-contrast typography sits atop layered, luminous backgrounds, ensuring clarity in high-density data environments without sensory exhaustion.

## Colors

The palette is engineered for WCAG AA compliance (minimum 4.5:1 for normal text, 3:1 for large text and graphical UI controls), prioritizing long-session optical comfort in back-office settings.

### Core Architecture
- **Primary (Action / Brand Teal):** `#0D9488` serves as the primary action anchor. For high-contrast interactive states, use `#0F766E` as the active base and `#115E59` for hover states.
- **Secondary (Deep Navy / Slate Base):** `#0F172A` provides anchoring depth for navigational headers, sidebars, and critical data titles. Intermediate surfaces leverage `#1E293B` and `#334155`.
- **Canvas & Neutral Surfaces:** Base viewport background is `#F8FAFC`. Card surfaces, modal sheets, and active form controls utilize pure `#FFFFFF`. Low-contrast structural borders rely on `#E2E8F0` with subtle dividers in `#F1F5F9`.

### Functional Status System
Every status state must combine a distinct tinted surface, an accessible text token (AA compliant), and an explicit icon token. Color must never be the sole carrier of semantic state.
- **Pendente (Processing / Awaiting Reconciliation):** Foreground `#B45309` on background `#FEF3C7`, paired with a circular processing or clock icon.
- **Aprovada (Settled / Completed):** Foreground `#047857` on background `#D1FAE5`, paired with a solid verification checkmark icon.
- **Rejeitada / Falhou (Failed / Reversed):** Foreground `#B91C1C` on background `#FEE2E2`, paired with an alert octagram or cross mark.

### Focus & Accessibility Indicators
Interactive controls require a dual-ring focus state: a 2px inner ring of `#FFFFFF` followed by a 2px outer ring of `#0D9488` with a 2px offset.

## Typography

The typographic hierarchy prioritizes rapid scanning of tabular ledger data, currency formulations in Brazilian Real (BRL), and transactional metadata.

### Typographic Roles
- **Primary Typeface (`Inter`):** Powers all headlines, body copy, and UI controls. Tabular figures (`tnum`) and slashed zeros must be enabled globally for numerical tables and financial input fields to maintain column alignment.
- **Code & Identifier Typeface (`JetBrains Mono`):** Applied to transactional references, idempotency keys, SHA256 hashes, PIX end-to-end IDs, and UUID tokens. Provides distinct letterforms to eliminate ambiguity between `0` and `O` or `1` and `l`.

### Numerical Formatting Rules
- **Currency (BRL):** Symbol (`R$`) should be rendered with `body-sm` or `headline-sm` at 70% opacity, while the integer and decimal components adopt primary headline weight. Decimal fractions must match the parent baseline.
- **Identifiers:** Truncate raw UUIDs with a centered ellipsis (`3f9a...8c21`) on compact screens, expanding on hover or click-to-copy interactions.

## Layout & Spacing

The layout is built upon an 8-point spatial grid with a 4-point micro-scale for precise alignment in dense data tables and key-value summaries.

### Canvas Structure
- **Desktop (>= 1280px):** Fixed-width left navigation rail (260px) alongside an unbounded fluid content canvas with a 1440px maximum read container. Margins default to `2rem` (32px), with `1.5rem` (24px) gutters between analytical metric widgets.
- **Tablet (768px - 1279px):** Left navigation collapses to an icon rail (72px). Inner canvas gutter transitions to `1.25rem` (20px), wrapping multi-column transaction tables into horizontally scrollable regions with persistent frozen columns for transaction IDs.
- **Mobile (< 768px):** Single-column vertical reflow. Sidebar translates into an off-canvas drawer. Outer margins reduce to `1rem` (16px), and cards collapse to full viewport width with flush horizontal rules.

## Elevation & Depth

Visual hierarchy relies on low-contrast structural outlines paired with light, ambient diffusion. Surfaces sit in clear planes to prevent visual noise during continuous monitoring.

### Elevation Levels
- **Layer 0 (Canvas Base):** Non-elevated surface set to `#F8FAFC`.
- **Layer 1 (Card & Content Container):** Pure white `#FFFFFF` surface enclosed by a 1px border of `#E2E8F0`. Shadow is soft and dispersed: `box-shadow: 0 1px 3px 0 rgba(15, 23, 42, 0.04), 0 1px 2px -1px rgba(15, 23, 42, 0.02)`.
- **Layer 2 (Dropdowns, Popovers & Context Panels):** Elevated `#FFFFFF` container with a 1px border of `#E2E8F0`. Shadow: `box-shadow: 0 10px 15px -3px rgba(15, 23, 42, 0.08), 0 4px 6px -4px rgba(15, 23, 42, 0.03)`.
- **Layer 3 (Modals, Slide-over Drawers & Critical Sheets):** Surface `#FFFFFF` supported by an ambient backdrop overlay tinted with `#0F172A` at 40% opacity with a 4px blur (`backdrop-filter: blur(4px)`). Shadow: `box-shadow: 0 20px 25px -5px rgba(15, 23, 42, 0.12), 0 8px 10px -6px rgba(15, 23, 42, 0.04)`.

## Shapes

The geometric identity balances clean engineering structure with approachable softness using an intentional baseline radius scale.

### Radius Conventions
- **Base Components (`rounded`, 0.5rem / 8px):** Used for standard input fields, dropdown trigger targets, buttons, code blocks, and context menus.
- **Composite Containers (`rounded-lg`, 1rem / 16px):** Standard application for dashboard cards, transaction summary tables, batch configuration blocks, and metric summary tiles.
- **Floating Modals & Drawers (`rounded-xl`, 1.5rem / 24px):** Applied to dialog boxes, payout execution overlays, and drawer corner bounds.
- **Pill Formats (`rounded-full`):** Reserved exclusively for semantic status badges, interactive avatars, and operational count indicators.

## Components

### Buttons
- **Primary:** Background `#0D9488`, label `#FFFFFF`, font weight 600. On hover, background shifts to `#115E59`. Active press resolves to `#0F766E`. Height is 40px (desktop) or 48px (mobile), with horizontal padding of `space-md` (16px).
- **Secondary (Outlined):** Background `#FFFFFF`, 1px border `#E2E8F0`, label `#1E293B`. On hover, border transitions to `#CBD5E1` with a background of `#F8FAFC`.
- **Ghost:** Background transparent, label `#334155`. On hover, background transitions to `#F1F5F9`.
- **Critical Action:** Background `#B91C1C`, label `#FFFFFF`. Hover resolves to `#991B1B`.

### Status Badges & Chips
Badges use `rounded-full`, a compact vertical height of 24px, horizontal padding of 10px, and `label-sm` monospaced or dense sans-serif text.
- **Pendente:** Background `#FEF3C7`, border 1px solid `#FDE68A`, text `#B45309`. Accompanied by a leading 12px pulsating or static clock icon.
- **Aprovada:** Background `#D1FAE5`, border 1px solid `#A7F3D0`, text `#047857`. Accompanied by a leading 12px verified checkmark.
- **Rejeitada:** Background `#FEE2E2`, border 1px solid `#FECACA`, text `#B91C1C`. Accompanied by a leading 12px alert triangle or cross icon.

### Input Fields & Currency Inputs
- Container height is 40px with a 1px border of `#CBD5E1` and radius of 8px. Background is `#FFFFFF`.
- Inactive text uses `#0F172A`, placeholder uses `#94A3B8`.
- Focus ring displays 2px outer teal `#0D9488` with a 2px offset.
- For currency (BRL), anchor a fixed leading addon containing "R$" in `#64748B` with a solid vertical divider `#E2E8F0`. Tabular numerical input aligns right to facilitate calculation review.

### Cards & Metrics
- Enclosed with a 1px border of `#E2E8F0`, 16px radius, and `#FFFFFF` background.
- Metric cards feature an upper label in `body-sm` (`#64748B`), followed by the value in `display-lg` (`#0F172A`).
- If an asynchronous queue count or processing trend is indicated, display the delta badge in the card top-right corner.

### Data Tables & Transaction Rows
- Table header row uses `#F8FAFC` with a 1px bottom border of `#E2E8F0`. Header labels are uppercase `label-sm` (`#64748B`).
- Table rows use alternating or hover-highlight states: default `#FFFFFF`, hover `#F8FAFC`.
- Cells are padded with `space-sm` vertically (12px) and `space-md` horizontally (16px).
- UUIDs and End-to-End transaction IDs are wrapped in a monospaced badge (`label-sm`) with a copy button accessible on row hover.