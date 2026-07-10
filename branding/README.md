# AgenC Android brand assets

`agenc-logo.svg` is the canonical square AgenC mark used by the Android app. It
was sourced from the public `agenc.tech/agenc-logo.svg` geometry and kept as an
SVG so future platform assets can be checked against one vector source.

Derived Android assets:

| Asset | Use | Conversion rules |
| --- | --- | --- |
| `drawable/ic_launcher_foreground.xml` | adaptive launcher foreground | exact mark paths converted to Android VectorDrawable, optically scaled inside 108dp safe area |
| `mipmap-anydpi-v26/ic_launcher*.xml` | adaptive/round launcher definitions | black background + white foreground |
| `mipmap-anydpi-v33/ic_launcher*.xml` | themed monochrome launcher | same mark used as monochrome layer |
| `drawable/ic_notification.xml` | status-bar and expanded notification small icon | opaque white alpha mask on transparent, padded to Android's 24dp safe area |
| `drawable-nodpi/agenc_mark.png` | raster in-app fallback/preview | black-and-white mark; do not use as notification small icon |
| `drawable/agenc_wordmark.xml` | in-app wordmark | separate `agenc` logotype geometry, white/tintable for dark surfaces |

## Update procedure

1. Replace the canonical SVG only with an approved AgenC source.
2. Preserve its viewBox/path geometry when converting to VectorDrawable.
3. Keep launcher/notification art monochrome; Android supplies themed/system
   colors.
4. Do not replace the notification mask with a generic bell, terminal, robot,
   or wallet glyph.
5. Verify normal, round, and themed launcher previews plus status-bar and
   expanded-notification rendering on a physical device.
6. Check that no raster background or embedded color produces a solid square
   in the status bar.

The app's product palette remains black/white. Violet is a semantic exception
for `@ledger`, not part of the launcher mark.
