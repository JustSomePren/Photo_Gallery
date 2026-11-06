# Galerie (offline)

Jednoduchá galerie pro Pixel 9 Pro (Android 13+). Běží úplně offline – aplikace nemá
oprávnění k internetu. Co neumí sama (úpravy, hledání, přehrávání videí), předá Google Fotkám.

## Co umí

- **Fotky** – všechny fotky a videa po měsících.
- **Oblíbené** – používá systémové oblíbené, takže je vidí i Google Fotky.
- **Alba** – složky v telefonu (Fotoaparát, Snímky obrazovky…) + vlastní alba.
- **Výběr více fotek** – dlouhý stisk na fotku, pak klepáním přidáváš další.
  Klepnutím na měsíc vybereš celý měsíc, ikonou v albu celé album.
- **Poslat do cloudu** – vybrané fotky pošle do zvoleného cíle (např. „Osobní – Fotky“,
  „Společný – Disk“). Cíle si nastavíš v *Nastavení → Kam posílat fotky*.
- **Upravit / Hledat** – otevře Google Fotky.

## Instalace na telefon (krok za krokem)

1. Na GitHubu otevři záložku **Actions** → poslední běh **Build APK**.
2. Dole v sekci *Artifacts* stáhni `galerie-apk` a rozbal ZIP.
3. Soubor `app-release.apk` pošli do telefonu a otevři ho.
4. Povol instalaci z neznámých zdrojů, když se telefon zeptá.
5. Při prvním spuštění povol přístup k fotkám a videím.

Nová verze se nainstaluje přes starou, alba a nastavení zůstanou (APK je vždy podepsané stejným klíčem `app/debug.keystore`).

Sestavení na počítači: `./gradlew assembleRelease` (potřebuje Android SDK).

## Baterie

Úsporný režim se zapne sám, když baterie klesne pod nastavenou hranici (výchozí **70 %**)
a telefon se nenabíjí, nebo když je zapnutý systémový spořič. Pak:

- černé pozadí (OLED displej černé pixely nesvítí),
- displej 60 Hz místo 120 Hz,
- menší náhledy a nenačítají se sousední fotky dopředu,
- nové fotky se nenačítají průběžně, ale až při dalším otevření aplikace.

Aplikace nemá vlastní indexaci – čte to, co už Android zaindexoval. Jediná práce na pozadí
je příprava náhledů a ta běží **jen při nabíjení** (dá se vypnout v Nastavení).

### Google Fotky na pozadí

Galerie nemůže Google Fotky ovládat. Ručně ale jde:

1. *Nastavení → Omezit Google Fotky na pozadí* → **Baterie → Omezeno**.
   Fotky pak zálohují hlavně, když je otevřeš.
2. Nebo v Google Fotkách zálohu na výletě dočasně vypni.

Volbu „zálohovat jen při nabíjení“ Google Fotky odstranily v roce 2017. Podle rozboru
aplikace (Android Authority) se možná vrátí, ale nepodařilo se mi ověřit, že už ji nabízí.

## Výběr účtu při odesílání

- **Google Disk** se při nahrávání vždy zeptá, na který účet.
- **Google Fotky** nahrají do účtu, který je v nich právě aktivní.

Doporučení: společný cloud přes Disk (sdílená složka), osobní přes Fotky.
Cílů si můžeš přidat kolik chceš, každý s vlastním názvem.

Pokud chceš potvrzovat oblíbené bez dialogu, povol *Nastavení → Oblíbené bez potvrzování*.
