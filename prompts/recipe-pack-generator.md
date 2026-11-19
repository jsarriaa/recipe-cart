# Recipe pack generator prompt

A prompt to paste into an **external** Claude chat (claude.ai or the mobile app). It makes Claude
answer with recipe files that CocinaParaLechugones can import: format `cpl-recipe/1`, recipe text
in Spanish (Spain).

It must stay in sync with [docs/RECIPE_FORMAT.md](../docs/RECIPE_FORMAT.md). Any format change
updates this prompt in the same PR.

## How to use

1. Copy everything inside the box below (on GitHub, use the copy button of the code block).
2. Paste it into a new chat and replace the text under **MI PETICIÓN** with what you want.
3. Claude answers with a `.zip` or `.md` file (when it can create files), or with one code block
   per recipe.
4. On the phone: share the file, or the copied text of a single recipe, to "Cocina para
   Lechugones", or use "Importar recetas" and pick the file. Check the preview, then tap
   "Aceptar todo" on the product suggestions.

Tips:

- Say how many recipes, servings, time limits, equipment ("sin horno", "air fryer"), diet, and
  whether they should share ingredients (fewer leftovers).
- To get several recipes as one file, ask for "un único .zip".

## The prompt

````text
You write recipes for a personal Android app called "CocinaParaLechugones". The app imports recipe files in a strict format named cpl-recipe/1, links each ingredient to real products from Spanish supermarkets (Mercadona, Día), and computes how much of each package a dish uses and what it costs. Your output is parsed by a machine, so follow the format exactly.

LANGUAGE
- Write every human-readable value in Spanish from Spain (es-ES): titles, descriptions, ingredient names, notes, groups, tags and steps. Use Spain's vocabulary: patata (not papa), nata (not crema de leche), zumo (not jugo), judías verdes, pimiento, gambas, maíz dulce, carne picada, frigorífico or nevera.
- Field names and unit codes are fixed English or code identifiers. Never translate them.

OUTPUT
- If you can create files: create one file per recipe named <slug>.md (lowercase ASCII, hyphens). If there is more than one recipe, or if I ask for a zip, put all of them at the root of a single .zip file. Do not add images.
- If you cannot create files: output each recipe in its own fenced code block with the language "markdown". Put the file name in bold on the line just before each block, for example **tacos-de-pollo.md**.
- Before the files write at most two short lines in Spanish summarising the pack. Write nothing after the files.
- If my request is ambiguous, do not ask questions. Make reasonable choices and mention them in those two lines.

FILE STRUCTURE
Each file is exactly: a line with ---, the YAML front matter, a line with ---, a blank line, then the Markdown body.

YAML FRONT MATTER (YAML 1.2)
Top-level fields, in this order:
- format: always cpl-recipe/1 (required, first line after ---)
- title: 1-120 characters (required)
- description: one or two sentences, at most 500 characters (optional, recommended)
- servings: integer 1-50 (required)
- prep_minutes, cook_minutes: integers 0-1440 (optional, recommended)
- tags: flow list of 1-5 lowercase Spanish tags, e.g. [cena, rápida, vegetariana] (optional)
- ingredients: list of 1-60 ingredients (required)
Do NOT write uid, image, source, author or links fields.

Each ingredient has these fields, in this order:
- name: generic Spanish name, lowercase, singular unless the food is naturally plural ("garbanzos cocidos"). No quantity, no brand, no preparation in the name. Use the same name for the same food across recipes. Examples: "contramuslo de pollo deshuesado", "tomate triturado", "cebolla", "nata para cocinar".
- quantity: a number with a dot as decimal separator (300, 0.5, 1.5) or a fraction in quotes ("1/2", "1 1/2"). Required, except: optional for unit pizca, and forbidden for unit al_gusto.
- unit: exactly one of the codes below.
- unit_weight_g: grams in ONE unit of this ingredient. Add it whenever unit is ud, diente, loncha, rebanada, hoja, ramita, cdta, cda or taza and the ingredient is not a liquid. Examples: 1 ud cebolla = 150, 1 ud aguacate = 200, 1 ud huevo = 60, 1 diente de ajo = 5, 1 cda de comino molido = 6, 1 cda de harina = 10, 1 tortilla de trigo maxi = 60.
- note: preparation or state, at most 120 characters ("picada fina", "en tiras", "a temperatura ambiente"). Optional.
- group: section label, at most 40 characters, only when the recipe has distinct parts ("Para la salsa", "Para el rebozado"). Optional.
- optional: true only for truly optional ingredients. Omit otherwise.
- pantry: true for pantry staples. Omit otherwise. Staples are: salt, pepper, oils, vinegar, sugar, dried herbs and spices, stock cubes, and anything used in tiny amounts (1 cda or less) that people keep at home.
- match: product-matching hints. Write it for EVERY ingredient, staples included:
    query: the words you would type in a Spanish supermarket's search box, in Spanish, no quantities, no brand. Mirror how products are named in Spanish supermarkets: "tomate triturado", "pechuga pollo filetes", "queso rallado mozzarella", "maxi tortillas trigo", "garbanzos cocidos".
    package_size: the typical package in Spain. Grammar: optional "<count> x " then amount, a space, then one of g, kg, ml, cl, l, ud. Examples: "500 g", "1 kg", "1 l", "200 ml", "6 ud", "12 ud", "3 x 200 ml", "4 x 125 g". Never write words like "paquete" or "lata" here.
    store_brand_ok: true if a store brand (Hacendado, Dia) or an unbranded fresh product is fine, which is almost always. Use false only when a specific brand really matters, and then put the brand in query.

UNIT CODES (use exactly these, lowercase)
- g, kg: mass
- ml, cl, l: volume
- cdta (cucharadita, 5 ml), cda (cucharada, 15 ml), taza (250 ml): kitchen volume, only for small amounts or liquids
- ud: whole items (eggs, onions, avocados, tortillas, lemons)
- diente (garlic clove), loncha (slice of cheese or ham), rebanada (slice of bread), hoja (leaf), ramita (sprig)
- pizca: a pinch. al_gusto: "to taste", with no quantity
Never use package words as units (lata, sobre, paquete, bote, brick, vaso, chorrito, puñado). Convert them: "1 lata de tomate triturado" becomes quantity 400, unit g, package_size "400 g". "Un chorrito de aceite" becomes 1 cda. "Un puñado de espinacas" becomes 30 g.

QUANTITIES
- Realistic for the stated servings, in amounts people actually buy and use in Spain.
- Prefer g or ml for anything weighed or measured. Use ud for whole items, with unit_weight_g.
- Prefer amounts that use whole packages or simple fractions of typical packages (250 g of a 500 g pack) to reduce leftovers. When several recipes are requested, reuse ingredients across them where it makes sense.

MARKDOWN BODY
- "## Preparación" followed by a numbered list of steps, one clear action per step, with times, heat level and doneness cues ("hasta que esté dorado").
- Optionally "## Notas" with a short bullet list: substitutions, storage, tips.
- No other sections, no ingredient list in the body (the front matter is the ingredient list), no HTML, no images, no links.

YAML SAFETY
- Two-space indentation, never tabs. Ingredients are "  - name: ..." items under "ingredients:".
- Booleans are only true or false.
- Wrap a value in double quotes if it contains ": " or " #", or starts with one of [ ] { } , & * ! | > ' " % @ or a backtick. Quote fractions: "1/2".

SELF-CHECK BEFORE ANSWERING (fix anything that fails; do not print this checklist)
1. The first line is --- and the next is format: cpl-recipe/1. The front matter is closed with ---.
2. title, servings and ingredients are present. servings is an integer.
3. Every unit is one of the codes above. No package words as units.
4. Every ingredient has a quantity, except pizca (optional) and al_gusto (none).
5. Every ud, diente, loncha, rebanada, hoja, ramita, cdta, cda or taza ingredient that is not a liquid has unit_weight_g.
6. Every ingredient has match with query, package_size and store_brand_ok. Every package_size follows the grammar.
7. Names are generic, lowercase and brand-free. Staples have pantry: true.
8. The body starts with "## Preparación" and a numbered list.
9. All text is Spanish from Spain.

EXAMPLE OF ONE VALID FILE (tacos-de-pollo.md)
```markdown
---
format: cpl-recipe/1
title: Tacos de pollo con aguacate y mozzarella
description: Tacos rápidos de contramuslo de pollo con especias tex-mex, tortillas maxi, aguacate, tomate cherry y mozzarella fundida.
servings: 2
prep_minutes: 15
cook_minutes: 15
tags: [mexicana, cena, rápida]
ingredients:
  - name: contramuslo de pollo deshuesado
    quantity: 300
    unit: g
    note: sin piel, en tiras finas
    match:
      query: contramuslos pollo deshuesados
      package_size: 500 g
      store_brand_ok: true
  - name: tortilla de trigo maxi
    quantity: 4
    unit: ud
    unit_weight_g: 60
    match:
      query: maxi tortillas trigo
      package_size: 6 ud
      store_brand_ok: true
  - name: aguacate
    quantity: 1
    unit: ud
    unit_weight_g: 200
    note: maduro
    match:
      query: aguacate
      package_size: 1 ud
      store_brand_ok: true
  - name: tomate cherry
    quantity: 150
    unit: g
    note: en cuartos
    match:
      query: tomates cherry
      package_size: 500 g
      store_brand_ok: true
  - name: mozzarella rallada
    quantity: 60
    unit: g
    match:
      query: queso rallado mozzarella
      package_size: 200 g
      store_brand_ok: true
  - name: sazonador para fajitas
    quantity: 10
    unit: g
    note: unos dos tercios de un sobre
    match:
      query: sazonador fajitas
      package_size: 17 g
      store_brand_ok: true
  - name: aceite de oliva virgen extra
    quantity: 1
    unit: cda
    pantry: true
    match:
      query: aceite oliva virgen extra
      package_size: 1 l
      store_brand_ok: true
  - name: sal
    unit: pizca
    pantry: true
    match:
      query: sal fina
      package_size: 1 kg
      store_brand_ok: true
---

## Preparación

1. Corta los contramuslos en tiras finas y mézclalos en un bol con el sazonador para fajitas.
2. Calienta el aceite en una sartén grande a fuego medio-alto y saltea el pollo 6-8 minutos, hasta que esté dorado por fuera y hecho por dentro.
3. Mientras, corta los tomates cherry en cuartos y el aguacate en láminas.
4. Prueba el pollo y añade la pizca de sal solo si hace falta: el sazonador ya lleva sal.
5. Calienta las tortillas en una sartén seca, unos 20 segundos por cada lado.
6. Reparte el pollo sobre las tortillas, cúbrelo con la mozzarella para que se funda y termina con el aguacate y los tomates.

## Notas

- Con un chorrito de zumo de lima sobre el aguacate queda más fresco y no se oscurece.
```

MI PETICIÓN
[Describe aquí lo que quieres. Ejemplo: "5 cenas rápidas para 2 personas, menos de 30 minutos, sin horno, que compartan ingredientes para que sobre poco. En un único .zip."]
````
