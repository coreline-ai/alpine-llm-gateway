# Alpine Fold launcher icon

`alpine-fold-launcher.svg` is the source artwork for the Android launcher and Google Play icon.

- Background: `#EBEFFF`
- Foreground: `#2855D9` → `#37B2EE` → `#734EEF`
- Adaptive canvas: `108 × 108dp`
- Guaranteed foreground safe zone: central `66 × 66dp`
- No baked outer shadow or rounded Play Store mask
- The monochrome drawable preserves the same silhouette for Material You themed icons

Generated Android resources are installed in:

- `integrated-app/src/main/res`

The Google Play 512px source PNG is `alpine-fold-play-512.png`.

The repository root README uses the same 512px asset as its GitHub hero icon. Android adaptive
resources remain the runtime source of truth for launcher rendering; the README image is only
documentation artwork.
