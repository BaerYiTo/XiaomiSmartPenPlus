# App icon

The icon is an original vector drawing created for Smart Pen Plus: a white diagonal stylus and a plus on a blue gradient. It contains no Xiaomi logo or other third-party artwork. The icon sources are provided under this project's license.

- `app-icon.svg`: editable, accessible SVG source; paths have no embedded images or fonts.
- `app-icon.png`: 512 × 512 preview for the project page.
- `app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml`: Android vector layers in a 108 × 108 viewport.
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`: adaptive launcher icon.
- `app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml`: adaptive icon with Android 13 themed-icon support.
- `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`: legacy launcher icons at 48, 72, 96, 144 and 192 pixels.

Essential artwork fits inside the central 66dp safe circle. Android launchers apply their own circle, rounded-square or other mask to the adaptive icon. The SVG and PNG previews show the central 72dp launcher viewport with rounded corners; the adaptive background fills its entire layer.

To regenerate PNG files, use Node.js with `sharp` installed, then run `node tools/render_icon.cjs`. An optional final argument writes an additional 1024px review preview. Keep the matching Android vector paths and SVG paths synchronized when editing the mark.
