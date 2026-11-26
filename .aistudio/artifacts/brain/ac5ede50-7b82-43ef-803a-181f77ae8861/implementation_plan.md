# Per-Layer VRAM Breakdown for Floating Resource HUD

Extend the draggable Floating Resource HUD with an expandable, read-only diagnostic panel that visualizes VRAM utilization for every GGUF transformer layer (weights + KV cache) across cluster nodes, helping users spot GPU memory bottlenecks and RAM spill points while configuring shard distributions.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The plan below incorporates all three design preferences confirmed during discovery. Review the layout, bottleneck thresholds, and read-only scope before proceeding.

- **Confirmed Layout**: Expandable section inside the existing floating HUD (remains compact by default and expands smoothly on demand).
- **Confirmed Visualization**: Horizontal stacked bars separating **Model Weights** and **KV Cache** per layer, paired with interactive bottleneck filters.
- **Confirmed Interactivity**: Strictly read-only diagnostic monitor (no mutation actions, config drawers, or `-nkvo` toggles embedded in the HUD).

---

## 1. Overview & Core Concept

- **What It Does**: Adds a collapsible **Layer VRAM Breakdown** section beneath the existing node telemetry cards inside the Floating Resource HUD. When expanded, it displays Master's pinned baseline overhead (embeddings, output head, and optional vision projector) followed by a scrollable per-layer breakdown showing each layer's assigned node, weight memory, KV cache footprint, cumulative VRAM pressure, and placement tier (`VRAM`, `Tight`, or `RAM Spill`).
- **Target Audience / Persona**: Local LLM engineers and power users tuning `--tensor-split`, context length (`-c`), KV cache quantization (`f16` / `q8_0` / `q4_0`), and GPU offload (`-ngl`) across heterogeneous Master + Worker clusters.
- **Key Value**: Eliminates guesswork when a GGUF model silently spills onto slower CPU RAM or hits a VRAM cliff at a specific layer index (e.g., dense vs. MoE expert layers or 32K+ context KV growth).

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Compact Default State**: Opening the HUD shows the familiar compact node cards (`CPU %`, `GPU %`, temperature, `tok/s`) plus a single summary disclosure trigger at the bottom: **Layer VRAM Breakdown** with a concise inline readout (e.g., `28 layers · 1.34 GB VRAM · 0 bottlenecks`).
2. **Expanding the Diagnostic View**: Clicking the disclosure button expands the HUD width cleanly (from compact `336px` to diagnostic `420px`, clamped within viewport bounds so dragging never clips off-screen) and reveals:
   - **Summary Memory Strip**: Total VRAM allocated vs. safe VRAM ceiling, pinned Master overhead, total KV footprint (noting when `-nkvo` keeps KV in CPU RAM), and count of detected bottleneck/spill layers.
   - **Read-Only Filter Bar**: Segmented filter buttons (`All Layers`, `Bottlenecks`, `RAM Spill`) plus an optional node selector when multiple compute nodes are active.
   - **Stacked Per-Layer List**: Compact, fixed-height rows (`L00`–`L(N-1)`) each showing:
     - Layer index and assigned node tag (clean inline metadata with `·` separators, no pill clutter).
     - Stacked horizontal bar scaled to the heaviest layer: **Emerald** segment for layer weights (`attention + FFN/experts`) and **Sky/Cyan** segment for KV cache (rendered in muted slate dashed pattern when KV is offloaded to CPU via `-nkvo`).
     - Right-aligned tabular metrics (`weights + KV = total MB`) and an explicit status word (`VRAM`, `Tight`, or `Spill`)—never relying on color alone.
3. **Zero-State & Empty Filter State**: If the user selects `Bottlenecks` or `RAM Spill` on a model that fits comfortably inside GPU VRAM, a crisp inline empty state confirms `"No VRAM bottlenecks detected for the current shard plan"` with a one-click `"Show All Layers"` reset.

### Visual Identity & Theme
- **Aesthetic Direction**: High-density developer telemetry console consistent with the existing Silica Cluster workspace—flat single-elevation surfaces, crisp 1px hairline dividers, and zero visual clutter.
- **Color Palette & Dual-Theme Parity**:
  - Neutral slate surfaces (`bg-white/95 dark:bg-[#0E1525]/95`, subtle dividers `border-slate-200 dark:border-slate-800`).
  - Semantic memory bars: **Weights** (`bg-emerald-500`), **VRAM KV Cache** (`bg-sky-500`), **CPU-Offloaded KV** (`bg-slate-400 dark:bg-slate-600`).
  - Bottleneck states paired with explicit text labels: Nominal VRAM (`text-emerald-700 dark:text-emerald-400`), Tight ≥ 85% VRAM or heavy layer spike (`text-amber-700 dark:text-amber-400`), RAM Spill (`text-rose-700 dark:text-rose-400`).
- **Typography & Tabular Discipline**: All layer indices (`L00`), megabyte readouts, cumulative percentages, and bottleneck counts strictly use `font-mono-num` (`tabular-nums`) for rock-solid vertical alignment. No static pill badges, no thick left accent borders, and no decorative pulsing dots.
- **Interactive Feedback & Motion**: Smooth, compositor-friendly transitions (`<= 200ms`) when toggling the expandable section and switching filters; drag bounds dynamically adapt when expanded so the HUD always stays reachable inside the viewport.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Deterministic Per-Layer VRAM Calculator Derived from Live Cluster Plan**
  - *Chosen Approach*: Compute per-layer VRAM utilization in a dedicated pure calculation module that takes the active GGUF model, current `StarClusterPlan`, and `BackendTuningConfig` (context length, KV quant, `-nkvo`, and per-node `safeVramMb` / `nglLayers`).
  - *Why*: Keeps the HUD 100% synchronized in real time whenever the user changes models, adjusts context length, changes `--tensor-split`, or plugs in a worker—even before `llama-server` is started—and allows deterministic unit testing of every bottleneck/spill boundary.
  - *Alternatives Considered*: Polling an external GPU profiler endpoint. Rejected because `llama-server` does not expose per-layer VRAM telemetry over HTTP and would leave the HUD blank while configuring shards offline.

- **Decision 2: Bottleneck Classification Criteria**
  - *Chosen Approach*: Flag a layer as a **Bottleneck** when any of three objective hardware conditions occur:
    1. **RAM Spill**: Layer falls past the node's GPU offload count (`nglLayers`) or pushes cumulative node VRAM above `safeVramMb`.
    2. **VRAM Pressure (`Tight`)**: Cumulative VRAM utilization on the assigned node reaches `>= 85%` of `safeVramMb`.
    3. **Memory Outlier**: A single layer's footprint exceeds `1.15×` the model's median layer size (e.g., dense vs. MoE expert transitions) or its VRAM KV cache exceeds `45%` of total layer memory at long context lengths.
  - *Why*: Captures both hard capacity cliffs (spill to system RAM) and soft scaling traps (KV cache bloat at 32K–131K context) with clear, explainable reasons per row.
  - *Alternatives Considered*: Highlighting only the top-N largest layers. Rejected because uniform dense models would falsely flag healthy layers even when plenty of VRAM headroom exists.

- **Decision 3: Viewport-Safe Expandable Geometry**
  - *Chosen Approach*: Keep the HUD at `336px` wide while collapsed and smoothly expand to `420px` wide (with a bounded `260px` scrollable layer list) when opened, automatically clamping horizontal drag coordinates so the expanded HUD never overflows the left or right viewport edge.
  - *Why*: Preserves an unobtrusive overlay during normal chat/monitoring while giving stacked bars and tabular MB columns enough breathing room to read without wrapping or clipping.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Flow

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                         Application Shell State                         │
│   activeModel (GgufModelPreset) · tuningConfig · plan (StarClusterPlan) │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │ props
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      Per-Layer VRAM Profiler (Pure)                     │
│  • Builds non-uniform layer profiles (weights + KV cache per layer)     │
│  • Maps L0..L(N-1) to assigned Master/Worker node & GPU offload (-ngl)  │
│  • Tracks cumulative VRAM vs safeVramMb & -nkvo CPU KV placement        │
│  • Classifies each layer: VRAM | Tight (>=85%) | RAM Spill + reasons    │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │ LayerVramBreakdownSummary
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Floating Resource HUD (Overlay)                     │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ Header (Drag Handle · Total tok/s · Pop-Out PiP · Close)          │  │
│  ├───────────────────────────────────────────────────────────────────┤  │
│  │ Active Node Cards (CPU · GPU · Thermal · tok/s)                   │  │
│  ├───────────────────────────────────────────────────────────────────┤  │
│  │ Expandable Disclosure: "Layer VRAM Breakdown" (aria-expanded)     │  │
│  │  ├─ Summary Strip (VRAM Used/Cap · Pinned Overhead · Bottlenecks) │  │
│  │  ├─ Read-Only Filters (All | Bottlenecks | RAM Spill) + Node Pick │  │
│  │  ├─ Master Pinned Overhead Row (Embeddings + Head + mmproj)       │  │
│  │  └─ Scrollable Stacked Bar Rows (Weights + KV Cache · Status)     │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State
- **Layer VRAM Entry**: Captures `layerIndex`, `nodeId`, `nodeLabel`, `nodeRole`, `weightMb` (`attentionMb + ffnOrExpertMb`), `kvCacheMb`, `kvInVram` (boolean reflecting `-nkvo`), `vramUsedMb`, `ramSpillMb`, `cumulativeNodeVramMb`, `nodeVramCapMb`, `nodeVramUtilPct`, `status` (`vram` | `tight` | `spill`), and `bottleneckReason`.
- **Breakdown Summary**: Aggregates `totalLayers`, `masterPinnedOverheadMb`, `totalVramAllocatedMb`, `totalVramCapacityMb`, `totalRamSpillMb`, `totalKvCacheMb`, `kvOffloadedToCpu`, `maxLayerMb` (for consistent bar scaling), `bottleneckCount`, `spillCount`, and per-node summaries.
- **Local HUD UI State**:
  - `layerBreakdownOpen` (`boolean`, collapsed by default to keep HUD compact)
  - `layerFilter` (`'all' | 'bottlenecks' | 'spill'`)
  - `selectedNodeId` (`'all' | string` when >1 active node exists)
  - `inspectedLayerIndex` (`number | null`, optional read-only inline detail row showing exact attention vs. FFN/expert MB, KV quant, and bottleneck cause on click)

### Interactive Component & State Mapping
- **Disclosure Toggle (`aria-expanded`)**: Expands/collapses the per-layer VRAM section and clamps HUD `right` offset to the active width (`336px` vs `420px`) so the panel remains inside the viewport.
- **Filter Buttons (`All`, `Bottlenecks`, `RAM Spill`)**: Deterministically filter the rendered layer list in-memory; empty filtered sets show a clear status message with a `"Show All Layers"` reset button.
- **Node Filter Selector**: Appears automatically when multiple compute nodes hold layers, allowing focused inspection of Master vs. individual workers.
- **Read-Only Layer Row Inspection**: Clicking a layer row toggles an inline detail readout (`Attention MB`, `FFN/Expert MB`, `KV Cache MB`, `Cumulative Node VRAM %`, and bottleneck explanation) without mutating any cluster configuration.
- **Verification Plan**: Unit-test the pure per-layer VRAM profiler across small dense models (fitting in VRAM), large 27B/70B models (triggering tight VRAM and RAM spill), MoE non-uniform layers, and `-nkvo` CPU KV offload; then verify the expanded HUD, stacked bars, bottleneck filters, and light/dark readability in a real browser.
