# Učící kvíz — Leadership & management

Jednoduchá webová aplikace (1 soubor `index.html`), stačí otevřít v prohlížeči — funguje offline i na mobilu.

## Funkce
- 115 otázek ABCD se správnými odpověďmi (z testových podkladů)
- **Adaptivní výběr otázek** podle tvých výsledků: nezodpovězené a chybné otázky se vracejí častěji, zvládnuté (3× správně za sebou) jen občas
- **Odpědi se míchají** při každém zobrazení + náhodná varianta znění otázky
- Režimy: Adaptivní učení / Celý test / Slabé otázky
- Pokrok se ukládá lokálně (localStorage), přehled stavu každé otázky
- Ovládání: kliknutí nebo klávesy 1–6, Enter = další otázka

## Databáze otázek
`otazky.json` — editovatelný seznam otázek (text, možnosti, správný index, varianty znění). Po úpravě je třeba data znovu promítnout do `index.html` (JSON je v něm zabudované).
