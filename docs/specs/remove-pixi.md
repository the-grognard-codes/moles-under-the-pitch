# DOM sprite review

The maintainer selected DOM/SVG for the live pitch in ADR 0003. The remaining development uses of PixiJS were the renderer comparison and the projected sprite proof. [Issue #615](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/615) records the original handoff after PR #612.

## Scope

Retire the renderer comparison and remove its dependency. Review sprites using the live pitch projection, placement anchors and scale. Preserve the page layout, kit recoloring, animation controls, accepted static fallback, 22-player scene and performance measurements. Keep the historical comparison, live match renderer, art pipeline and stadium studies intact.

## Acceptance

A clean install and production build succeed. Browser source, tests, styles and package manifests contain no references to the retired renderer. The retired URL follows the ordinary unknown-route behavior. Browser tests exercise the DOM pitch through clips, directions, mirror and kit changes. Preserve the plain canvas palette sheet coverage.

Amend ADRs 0003 and 0011. Run the browser, asset, proof and server checks. Record production and install sizes, frame intervals and paired screenshots for both ends, both projections and mirroring in the [implementation outcome](remove-pixi-outcome.md).
