# P2 — ciclul de viață al instrumentelor, 31 octombrie 2026

Publicația `f41d3a8d-10f6-4bf7-a606-33dc838fa7ff` rămâne la 497 scoruri
parțiale și 21 excluderi. Investigația nu aplică revizuiri, nu schimbă universul
și nu reprezintă o publicație nouă.

## Constatări verificate

- **WBD**, instrument `77f24b58-a12d-4483-b86f-9d695eb4324a`: Nasdaq confirmă
  ultima tranzacționare pe 5 octombrie 2026, închiderea fuziunii înaintea
  deschiderii din 6 octombrie și suspendarea din 7 octombrie. Lipsa prețului
  din 29 octombrie nu se repară prin retry. Nu se atribuie prețul cumpărătorului
  acțiunilor WBD și nu se înlocuiește prețul zilei cu ultimul preț istoric.
  Sursa: [Nasdaq ECA2026-710](https://www.nasdaqtrader.com/TraderNews.aspx?id=ECA2026-710).
- **PSKY**, instrument `8e26439e-70a8-4c8f-a52d-efdc67e4567e`, CIK
  `0002041610`: raportul SEC identifică trecerea acțiunilor Class B la
  **SKYD / NYSE**, pe 6 octombrie 2026. Necesită corectarea datată a
  simbolului/bursei, verificarea continuității clasei și recolectare auditată.
  Acest caz nu reprezintă dispariția instrumentului WBD și nu permite unirea
  seriilor celor două companii.
  Sursa: [SEC, Skydance 8-K](https://www.sec.gov/Archives/edgar/data/2041610/000110465926113913/tm2626659d7_8k.htm).
- **ARM/PDD** sunt active în referințele locale, dar au tipul
  `DEPOSITARY_RECEIPT`. Motivul combinat `INACTIVE_OR_UNSUPPORTED_SECURITY`
  nu trebuie interpretat drept dovadă că au fost delistate. Suportul necesită
  o politică versionată, fără redenumirea tipului în `COMMON_STOCK`.

[Starea referințelor DB](reference-state.json) arată că WBD este încă activă,
iar PSKY are simbolul deschis pe XNAS. Acestea sunt lipsuri ale referințelor
curente, nu modificări ale inputurilor deja publicate. Identitatea SPCX este
inventariată separat; coincidența simbolului nu rezolvă automat problema sa.

## Dovezi și limite

[Captura web](web-sources.json) este răspunsul randat al instrumentului web,
nu octeții HTTP originali. SHA-256:
`38293ee14fc6b9e67cb858e8cd949ae6421889b7bc916cb0f414d7963b354724`.
Momentul capturii este păstrat în fișier; nu este antedatat la eveniment.
Aceste constatări nu au fost aplicate în DB și nu elimină alte blocaje.

## Decizie acceptată la 31 octombrie 2026

Utilizatorul a acceptat păstrarea instrumentelor dispărute cu excluderi
economice documentate. Ținta fixă de 518 scoruri curente este înlocuită de
reconcilierea completă a universului, cu scoruri și asemenea excluderi.
Această decizie nu autorizează clasificarea tuturor lipsurilor drept economice.

Pentru publicația verificată, reconcilierea de lucru este **518 = 497 scoruri
parțiale + 1 excludere cu o cauză economică documentată (WBD) + 20 cazuri
active de investigat/remediat**. Aceasta este clasificarea auditului după
decizie, nu o modificare a celor 21 de excluderi publicate. WBD necesită în
continuare corectarea referințelor și verificarea motivului în publicația nouă.
PSKY/SKYD rămâne în cele 20 de cazuri; schimbarea simbolului nu justifică
abandonarea colectării. P2 rămâne deschis până la remedieri și verificare.
