# Community recipe book

Owner request after 1.2.0: people can search recipes others published, download them, and
publish their own as "Tacos tex mex · por Joan". The owner can edit or remove any recipe.

## Constraints

- No accounts, no analytics, no SDK that phones home (CLAUDE.md, hard rule 4).
- No secrets in the APK: anything inside it can be extracted, so the app cannot hold a GitHub
  token.
- Free to run, and the owner keeps full control of the content.

## Storage: a GitHub repository

[`jsarriaa/recetario`](https://github.com/jsarriaa/recetario) (public, CC BY 4.0 for recipes):

```
recipes/<slug>.md          one cpl-recipe/1 file per recipe, with the optional author field
index.json                 generated, never edited by hand
scripts/build_index.py     builds index.json (PyYAML)
.github/workflows/index.yml   runs it on every push to main that touches recipes/
```

`index.json`:

```json
{
  "format": "cpl-recetario/1",
  "recipes": [
    {
      "path": "recipes/tacos-de-pollo.md",
      "title": "Tacos de pollo con aguacate y mozzarella",
      "servings": 2,
      "uid": "ejemplo-tacos-de-pollo",
      "author": "jsarriaa",
      "description": "…",
      "total_minutes": 30,
      "tags": ["cena", "mexicana", "rápida"],
      "ingredients": ["contramuslo de pollo deshuesado", "…"]
    }
  ]
}
```

Only `path`, `title` and `servings` are always present. Recipes are listed newest first (by
the commit that added the file). Invalid files are left out with a warning in the workflow log.

**Moderation**: the owner edits or deletes files on GitHub (web editor or git; step by step in the [README](../README.md#editing-the-community-recipe-book-on-github)). The workflow
rebuilds the index about a minute later; `raw.githubusercontent.com` may serve the old copy for
up to 5 minutes.

## Reading (app 1.3.0)

`CommunityBook` downloads `index.json` when "Recetas de la comunidad" opens, and a recipe file
when the user taps "Descargar". The text goes to the normal import screen. `CommunitySearch`
(domain) filters by text, tags and time.

## Publishing (app 1.4.0)

1. The first "Publicar", on a recipe's menu, asks for the name to show ("por Joan") and keeps it
   in settings; it can be changed in Ajustes.
2. The app exports the recipe as `cpl-recipe/1` with `author` set and without product links, and
   POSTs it to a Cloudflare Worker owned by the owner.
3. The Worker validates size and basic fields, rate-limits by IP, and commits
   `recipes/<slug>-<random>.md` with a fine-grained GitHub token that can write only to this
   repository (kept as a Worker secret, never in the app).
4. The index workflow runs; the recipe appears in every app's search. The owner removes
   anything unwanted on GitHub.

Code: `CommunityPublication` (domain: text, name rules, refusing someone else's recipe),
`PublishViewModel` and `PublishDialogs` (app), `CommunityBook.publish`, and the Worker in
[`community/worker/`](../community/worker/) with its [setup guide](../community/worker/README.md).
The app only publishes once `CommunityLinks.PUBLISH_URL` holds the deployed Worker's address;
until then "Publicar" says it is not available.

Publishing keeps the recipe's `uid`, so publishing the same recipe twice creates two files;
the owner deletes the extra one.
