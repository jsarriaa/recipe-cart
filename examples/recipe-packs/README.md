# Example recipe packs

Sample files in the [`cpl-recipe/1` format](../../docs/RECIPE_FORMAT.md). They double as test
fixtures: the parser tests import every file here and expect **zero errors and zero warnings**
(SPEC AC3.4).

| File | What it shows |
|---|---|
| [tacos-de-pollo.md](tacos-de-pollo.md) | Single-recipe pack: mass and count units, `unit_weight_g` for count ingredients (tortillas, avocado), notes, pantry staples (`cda` of oil, `pizca` of salt), and match hints with package sizes taken from real Mercadona products |

## Making a `.zip` pack

Put several `.md` recipes at the root of a zip, and their images in an `images/` folder:

```
mi-pack.zip
├── tacos-de-pollo.md          # image: images/tacos-de-pollo.jpg
├── noquis-con-tomate.md
└── images/
    └── tacos-de-pollo.jpg
```

From PowerShell:

```powershell
Compress-Archive -Path .\tacos-de-pollo.md, .\images -DestinationPath .\mi-pack.zip
```

## Trying a pack on the phone

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" shell mkdir -p /sdcard/Download/CocinaParaLechugones-test
"$ADB" push examples/recipe-packs/tacos-de-pollo.md /sdcard/Download/CocinaParaLechugones-test/
```

Then open it from the Files app with "Compartir → Importar receta", or use "Importar recetas"
in the app. The expected result is one recipe with 8 ingredients, 2 of them in "Básicos de
despensa", and no warnings.

Expected package fractions with the Mercadona products found on 2026-11-18 (phase 4):

| Ingredient | Product (package) | Expected line |
|---|---|---|
| 300 g contramuslo | Contramuslos de pollo deshuesados y sin piel (bandeja ≈ 550 g) | ≈1/2 bandeja, 300 g de 550 g |
| 4 ud tortilla maxi | Maxi tortillas de trigo Hacendado (paquete 360 g, 6 ud) | 2/3 paquete, 4 ud de 6 ud |
| 1 ud aguacate | Aguacate (pieza ≈ 200 g) | 1 pieza, 200 g de 200 g |
| 150 g tomate cherry | Tomates cherry (bandeja 500 g) | ≈1/3 bandeja, 150 g de 500 g |
| 60 g mozzarella rallada | Queso rallado mozzarella Hacendado (paquete 200 g) | ≈1/3 paquete, 60 g de 200 g |
| 10 g sazonador | Sazonador para fajitas Hacendado (sobre 17 g) | ≈2/3 sobre, 10 g de 17 g |
