# Publishing Worker: setup guide

"Publicar en la comunidad" in the app sends the recipe to this small
[Cloudflare Worker](https://developers.cloudflare.com/workers/), which commits it to the
community recipe book ([jsarriaa/recetario](https://github.com/jsarriaa/recetario)). The Worker
holds the GitHub token, so the app never does. Design: [docs/COMMUNITY.md](../../docs/COMMUNITY.md).

Everything below is free and done once, in about 10 minutes, from a browser.

## 1. GitHub token (only for the book)

1. GitHub → your picture → **Settings → Developer settings → Personal access tokens →
   Fine-grained tokens → Generate new token**.
2. Name `recetario-worker`. Expiration: the longest offered (write the date down; the app's
   "Publicar" stops working when it expires, and you just create a new one).
3. **Repository access → Only select repositories → `jsarriaa/recetario`**.
4. **Permissions → Repository permissions → Contents → Read and write.** Nothing else.
5. Generate, and copy the token (it starts with `github_pat_`). Do not paste it anywhere else.

## 2. Cloudflare Worker

1. Create a free account at <https://dash.cloudflare.com/sign-up>.
2. **Workers & Pages → Create → Create Worker** (the "Hello World" template). Name it
   `recetario` and **Deploy**.
3. **Edit code**: replace everything with the contents of [`src/index.js`](src/index.js), then
   **Deploy**.
4. The Worker's **Settings → Variables and Secrets → Add**:
   - Type **Text**, name `REPO`, value `jsarriaa/recetario`.
   - Type **Secret**, name `GITHUB_TOKEN`, value the token from step 1.

   **Deploy** again if the dashboard asks.
5. Copy the Worker's address from its page, for example
   `https://recetario.<your-subdomain>.workers.dev`, and send it to whoever builds the app: it
   goes into `CommunityLinks.PUBLISH_URL`.

Rate limit: the dashboard setup limits each IP to 3 publications per minute per Worker
instance. For a global limit, deploy with `npx wrangler deploy` from this folder; `wrangler.toml`
adds Cloudflare's rate-limiting binding.

## Check it

```bash
curl -s -X POST https://recetario.<your-subdomain>.workers.dev/publish \
  -H "Content-Type: application/json" -d '{"recipe":"hola"}'
# {"ok":false,"error":"not_a_recipe"}
```

## Moderation

Every publication is a commit in `jsarriaa/recetario` ("feat: publish <title> (por <name>)").
Edit or delete the file on GitHub; the index updates by itself. To stop publishing entirely,
delete the token on GitHub or disable the Worker in Cloudflare: the app then says publishing
failed, and searching keeps working.

## Errors the Worker returns

| HTTP | `error` | Meaning |
|---|---|---|
| 422 | `not_a_recipe`, `title`, `author`, `too_large` | The text is not a publishable recipe |
| 429 | `rate_limited` | Too many publications from this IP in the last minute |
| 502 | `storage` | GitHub refused the commit (expired or wrong token, wrong `REPO`) |
| 400, 404, 405 | `bad_request`, `not_found`, `method` | Not a request from the app |
