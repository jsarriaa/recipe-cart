# supermercapy (MIT)

[jtayped/supermercapy](https://github.com/jtayped/supermercapy), Copyright (c) 2024 Joel Taylor,
MIT licence (full text in [LICENSE](LICENSE)).

It is a Python library that documents the public storefront APIs of Spanish supermarkets. The
providers marked "ported from supermercapy" in [docs/providers/](../../docs/providers/README.md)
follow its documentation (endpoints, field meanings, pacing, pitfalls) and translate its parsing
logic to Kotlin. Files named `supermercapy-*.json` or `supermercapy-*.html` under a provider's
`src/test/resources/fixtures/` are its recorded responses, copied unchanged.
