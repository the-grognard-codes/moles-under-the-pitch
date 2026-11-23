# Build the browser client with TypeScript, React and Vite

Status: accepted by the owner on 2026-09-06. [Acceptance and source](../../.notes/overhaul-analysis/04-technology-and-decisions.md#adr-002-typescript-react-dom-interface-pixijs-board-vite) · [September 6 revision](https://github.com/the-grognard-codes/moles-under-the-pitch/blob/120dd9953a71d7b0bff031c751feffae7f55b5f8/.notes/overhaul-analysis/04-technology-and-decisions.md#adr-002-typescript-react-dom-interface-pixijs-board-vite)

## Amendments and follow-up

- 2026-09-25. After the renderer parity review, the owner provisionally selected DOM/SVG for the pitch, pending integrated acceptance. TypeScript, React and Vite remain in use. The local Pixi parity route remains comparison evidence, not the product renderer. [Renderer parity decision](../../.notes/overhaul-analysis/m5-renderer-parity-review-2026-09-24.md)
- 2026-10-04. Local live-match acceptance passed with the DOM/SVG pitch, as [ADR 0003](0003-coach-oriented-angled-pitch.md#feasibility-with-the-selected-renderers) records. [Acceptance report](../specs/coach-oriented-match-ui-acceptance.md)
- 2026-11-23. The maintainer confirmed DOM/SVG as final, closing the provisional conditions of the 2026-09-25 selection. [Decision](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/612#issuecomment-7092566289) · [ADR 0003 amendment](0003-coach-oriented-angled-pitch.md#amendments)

## Original decision, 2026-09-06

Decision status: **accepted**, including React. The subsequent Svelte comparison did not change this selection.

**Proposed:** TypeScript for browser code, React for forms/panels/roster editing, CSS for layout, and PixiJS 8 with WebGL for the 2D board. Keep rendering behind a small board-view interface so it does not own rules or connection state. React and Pixi must not both independently mutate gameplay state.

| Renderer | Strength for this game | Cost/limitation | Decision |
|---|---|---|---|
| PixiJS | Sprite/overlay scene graph, GPU rendering, input handling; focused on presentation | Scene transitions/audio and accessible interactions need deliberate integration | **Preferred** for a rules-driven board with substantial DOM UI |
| Phaser | Integrated 2D game framework, input, scenes, animation and audio; JS/TS support | More framework lifecycle concepts to coordinate with forms and server state | Strong fallback if prototype benefits materially from its integrated tooling |
| Canvas 2D directly | Small dependency surface; easy basic board drawing | Own hit testing, zoom, asset management, redraw policy and accessibility bridge | Suitable for a disposable diagnostic viewer; less attractive as the growing product client |
| DOM/SVG board | Semantic controls and inspectable layout; strong for simple vector shapes | More manual layering and sprite/animation integration | Keep for controls and accessible companion view; reconsider for board if prototype proves simpler |

Pixi's current docs recommend WebGL for production, describe WebGPU as experimental, and list a Canvas renderer as coming soon. Therefore, **do not promise automatic Canvas fallback** with this choice: show a clear unsupported-renderer message if WebGL initialization fails, and test that path. Pixi accessibility is opt-in through DOM overlays; it does not make the game accessible automatically. [Pixi renderers](https://pixijs.com/8.x/guides/components/renderers); [Pixi accessibility](https://pixijs.com/8.x/guides/components/accessibility).

Phaser is a browser-oriented 2D framework with JavaScript/TypeScript support and WebGL/Canvas rendering. Pin a specific supported major before prototyping; do not copy the notes' Phaser 3 assumption as an audit conclusion. [Phaser introduction](https://docs.phaser.io/phaser/getting-started/what-is-phaser). React's component/event/state model fits the roster builder and panels; choosing it here is a maintainability judgment, not a performance finding. [React guide](https://react.dev/learn).

Use Node 24 LTS for the proposed frontend toolchain rather than an obsolete Node minimum. Vite documents minimum Node requirements, while Node's lifecycle page identifies supported release lines; revalidate both when pinning the implementation lockfile. Node is not a required game-server process in this design. [Vite requirements](https://vite.dev/guide/); [Node release status](https://nodejs.org/en/about/previous-releases).
