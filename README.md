# Galerie (offline)

Jednoduchá galerie pro Pixel 9 Pro (Android 13+). Prohlížení běží úplně offline.
Internet se použije jen k odeslání fronty do Google Fotek. Úpravy a hledání předává Google Fotkám.

## Co umí

- **Kalendář** (výchozí) – celý měsíc na obrazovce, víkendy vpravo, v každém dni první fotka
  a počet. Klepnutí na den otevře jeho fotky, **dlouhý stisk na den založí událost**.
  Ikonou nahoře přepneš na klasickou mřížku.
- **Události** – barevná období v kalendáři (třeba „Dovča Praha“). Klepnutím na událost
  uvidíš všechny její fotky.
- **Automatické odesílání** – u události zapneš jeden přepínač, vybereš Google účet a album
  (nepovinné). Všechny fotky z těch dní se samy pošlou do Google Fotek do toho alba.
- **Fronta** – vybrané fotky → Poslat → účet. Odešlou se samy na Wi-Fi (např. až budeš doma).
- **Záložka Odesílání** – fronta, události, Google účty a ruční cíle na jednom místě.
- **Fotky** – všechny fotky a videa po měsících.
- **Oblíbené** – používá systémové oblíbené, takže je vidí i Google Fotky.
- **Alba** – složky v telefonu (Fotoaparát, Snímky obrazovky…) + vlastní alba.
- **Výběr více fotek** – dlouhý stisk na fotku, pak klepáním přidáváš další.
  Klepnutím na měsíc vybereš celý měsíc, ikonou v albu celé album.
- **Poslat do cloudu** – vybrané fotky pošle do zvoleného cíle (např. „Osobní – Fotky“,
  „Společný – Disk“). Cíle si nastavíš v *Nastavení → Kam posílat fotky*.
- **Upravit / Hledat** – otevře Google Fotky.
- **Videa** – přehrávají se přímo v aplikaci (klepnutí = pauza, posuvník dole).
- **Informace o fotce** – datum, rozlišení, fotoaparát, expozice, poloha (tlačítko „Na mapě“).
- **Přesunout do složky** – mezi složkami v DCIM/ a Pictures/ nebo do nové složky.
- **Rychlé posouvání** – po posunutí se u pravého okraje objeví táhlo s měsícem.
- **Obnovení** – stáhni seznam dolů.
- Po zavření fotky zůstaneš na stejném místě v mřížce, otočení telefonu nic nezruší.

### Propojení s Kalendářem

Když je nainstalovaný i **Kalendář** (repozitář Calendar), propojí se samy:

- **Dovolená z Kalendáře** se ukáže jako událost (barevný pruh), v Albech v sekci *Události*
  a sama se posílá do účtu a alba, které jsi vybral v Kalendáři. Měnit se dá jen tam
  (*Otevřít v Kalendáři*), aby obě aplikace ukazovaly totéž.
- **Fotky k ostatním osobním událostem**: den, kde nějaká fotka patří k události z kalendáře,
  má nahoře tečku. Po otevření dne jsou nahoře štítky událostí (např. „Oslava · 12“),
  klepnutím uvidíš jen fotky z té doby (15 min před až 30 min po konci).
- Kalendář si od Galerie bere náhledy fotek, seznam Google účtů a polohu fotek (pokud ji mají).
- Kalendář (v obou aplikacích) i události z Kalendáře berou **jen fotky a videa z fotoaparátu**
  (složka DCIM), ne snímky obrazovky, stažené ani přijaté obrázky.
- Fotka dne v kalendáři: přednost má oblíbená, jinak nejostřejší (ostré fotky mají víc dat na pixel).

Změnu v Kalendáři Galerie zachytí hned, jinak při otevření a při odesílání na pozadí.

### Moje alba

Alba si pamatují fotky podle jména souboru a data, takže přežijí i přesun fotky i nový telefon (viz Záloha).

## Podpisový klíč (jednou, před první instalací)

Klíč není v repozitáři (je veřejný). Obě aplikace, Kalendář i Galerie, mají **stejný** klíč,
díky tomu se navzájem poznají a nová verze se vždy nainstaluje přes starou.

1. Na GitHubu otevři repozitář → **Settings** → **Secrets and variables** → **Actions**.
2. **New repository secret**, název `SIGNING_KEY`, hodnota = celý obsah souboru `SIGNING_KEY.txt`.
3. **New repository secret**, název `SIGNING_KEY_PASSWORD`, hodnota = obsah `SIGNING_KEY_PASSWORD.txt`.
4. Totéž udělej v druhém repozitáři (Calendar i Photo_Gallery).
5. V **Actions** spusť znovu poslední běh **Build APK** (bez klíče skončí chybou „Chybí secret SIGNING_KEY“).
   Hotové APK se objeví v **Releases** pod odkazem z části Instalace.

Soubor `podpisovy-klic.p12` a heslo si **schovej** (správce hesel nebo soukromý Disk).
Bez nich nepůjde vydat novou verzi, která se nainstaluje přes starou, a Google záloha se nevrátí.
Do repozitáře je nikdy nedávej.

Vlastní sestavení s tímto klíčem: nastav proměnné `SIGNING_KEY_FILE` (cesta k `.p12`) a `SIGNING_KEY_PASSWORD`.
Bez nich se APK podepíše vývojovým klíčem počítače a přes verzi z GitHubu se nenainstaluje.

## Záloha a nová instalace

Alba, události, Google účty, ruční cíle, fronta, nastavení i **seznam už odeslaných fotek**
se samy zálohují do Google účtu a po nové instalaci se vrátí. Díky seznamu odeslaných se nic
nepošle do Google Fotek podruhé.

- Telefon musí mít zapnuté *Nastavení → Google → Zálohování*.
- Záloha běží zhruba jednou denně (v noci při nabíjení na Wi-Fi), dnešní změny v ní ještě být nemusí.
- Funguje jen se stejným podpisovým klíčem (viz výše).

Před odinstalací pro jistotu: *Nastavení → Záloha → Uložit zálohu do souboru*, po instalaci
*Načíst zálohu ze souboru* (aplikace se pak sama spustí znovu). Načíst jde i starší soubor jen s alby.
Fotky samotné se neodinstalují, jsou v telefonu.

## Instalace na telefon (krok za krokem)

1. V telefonu otevři odkaz https://github.com/JustSomePren/Photo_Gallery/releases/download/nejnovejsi/galerie.apk
2. Stažený soubor otevři (v oznámení o stažení) a klepni na **Instalovat**.
3. Povol instalaci z neznámých zdrojů, když se telefon zeptá (jen poprvé).
4. Při prvním spuštění povol přístup k fotkám a videím.

Odkaz vede vždy na nejnovější verzi.

Nová verze se nainstaluje přes starou, alba a nastavení zůstanou (APK je vždy podepsané stejným klíčem, viz Podpisový klíč).

Sestavení na počítači: `./gradlew assembleRelease` (potřebuje Android SDK).

Testy: `./gradlew testDebugUnitTest`. Kromě logiky vykreslí i obrazovky aplikace s ukázkovými
fotkami do `app/build/outputs/roborazzi/` (na GitHubu jako artefakt **obrazovky**).

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

## Automatické odesílání – nastavení (jednou, asi 10 minut)

Google vyžaduje, aby si aplikaci, která nahrává do Google Fotek, zaregistroval její majitel.

1. Otevři [console.cloud.google.com](https://console.cloud.google.com) a vytvoř nový projekt (např. „Galerie“).
2. *APIs & Services → Library* → vyhledej **Photos Library API** → **Enable**.
3. *APIs & Services → OAuth consent screen* (Google Auth Platform):
   typ **External**, vyplň název a svůj e-mail, ulož.
4. V části **Audience / Test users** přidej **všechny účty**, do kterých chceš posílat
   (osobní i společný, např. SharedXXO).
5. *Credentials → Create credentials → OAuth client ID* → typ **Android**:
   - Package name: `com.justsomepren.gallery`
   - SHA-1: `BF:C5:D1:98:01:55:77:D6:95:C5:7C:89:7E:39:10:0A:46:07:29:DE`
6. V aplikaci: *Odesílání → Google účty → +* → vyber účet → potvrď.

Pokud Google po týdnu chce přihlášení znovu (u projektů v testovacím režimu se to může stát),
v Odesílání se u účtu objeví „Přihlásit“.

### Jak se odesílá

- Jen na **Wi-Fi** (mobilní data jde zapnout v Odesílání).
- Pod hranicí baterie (výchozí 70 %) **jen při nabíjení**.
- Tlačítko **Odeslat hned** pošle frontu okamžitě i přes data a bez ohledu na baterii.
- Každá fotka se do stejného účtu pošle jen jednou.
- Album se v Google Fotkách vytvoří samo. Aplikace smí přidávat jen do alb, která sama vytvořila.

## Ruční odesílání přes aplikace

- **Google Disk** se při nahrávání vždy zeptá, na který účet.
- **Google Fotky** nahrají do účtu, který je v nich právě aktivní.

Doporučení: společný cloud přes Disk (sdílená složka), osobní přes Fotky.
Cílů si můžeš přidat kolik chceš, každý s vlastním názvem.

Pokud chceš potvrzovat oblíbené bez dialogu, povol *Nastavení → Oblíbené bez potvrzování*.
