# Galerie (offline)

Jednoduchá galerie pro Pixel 9 Pro (Android 13+). Prohlížení běží úplně offline.
Internet se použije jen k odeslání fronty do Google Fotek. Úpravy a hledání předává Google Fotkám.

## Co umí

- **Mřížka fotek** (výchozí) – fotky po dnech („Dnes“, „Včera“, „Čtvrtek 5. 11.“) uvnitř měsíců;
  v Nastavení jde přepnout jen na měsíce. Kde začíná událost, je barevný řádek
  s jejím názvem, pod fotkami z události barevný proužek a při posouvání plave nahoře datum a událost.
- **Kalendář** – ikonou nahoře; celý měsíc na obrazovce, víkendy vpravo. Klepnutí na den otevře
  jeho fotky, **dlouhý stisk na den založí událost**.
- **Události** – barevná období v kalendáři (třeba „Dovča Praha“). Klepnutím na událost
  uvidíš všechny její fotky.
- **Najít výlety** – *Nastavení → Výlety*, spustí se jen tímto tlačítkem. Podle polohy ve fotkách
  z fotoaparátu najde výlety (i staré): domov je místo s fotkami v nejvíc dnech, souvislé dny dál než
  50 km od něj jsou výlet. Název podle země, doma podle města (např. „Tchaj-wan – říjen 2025“).
  Návrhy potvrdíš a vzniknou z nich události, případně alba v aplikaci. Vše jen v telefonu;
  polohy se čtou jednou a pamatují, další hledání čte jen nové fotky.
  **Fotky bez polohy** (vypnutá poloha ve fotoaparátu):
  - *Načíst Časovou osu* – export Časové osy Google z telefonu (Timeline.json) doplní fotkám polohu
    podle času pořízení; nejpřesnější, pokud byla Časová osa zapnutá.
  - Časové pásmo ve fotce – pozná cestu do zahraničí („Výlet UTC+8“), pokud ho telefon zapsal.
  - Dny s mnohem víc fotkami než obvykle – návrh „Možná výlet“.

  Počet fotek se bere v potaz vždy: „hodně“ znamená víc než obvykle v tom období (±45 dní), takže
  sedí i když fotíš víc než dřív. Výlet podle polohy či pásma se rozšíří o sousední dny s návalem
  fotek bez polohy (den odletu) a klidnější den uprostřed dovolené ji nerozdělí. U návrhu je vidět
  proč, např. „podle polohy · 5× víc fotek než obvykle“.
- **Uvolnit místo** – *Nastavení → Uvolnit místo*. Smaže z telefonu jen fotky a videa, které tahle
  aplikace nahrála do Google Fotek a které tam jsou ověřeně: Google při kontrole potvrdí, že položka
  v účtu je se stejným názvem, a soubor v telefonu má stejnou velikost jako při nahrání. Bez internetu
  se nic nesmaže a oblíbené lze ponechat. Ověřené fotky vidíš v mřížce a sám vybereš, které smazat;
  videa jen ta, která Google už plně zpracoval. Pak potvrdíš v aplikaci (vždy, i se „správou médií“)
  a těsně před smazáním se každá vybraná fotka znovu ověří u Googlu – co neprojde, zůstane.
  Nakonec ještě dialog Androidu (ten se „správou médií“ vynechá). Jinde aplikace nic nemaže. Fotky zálohované
  samotnými Google Fotkami ověřit nejde (Google je jiným aplikacím neukáže) – na ty je Uvolnit místo
  v Google Fotkách. Ověření potřebuje jednou povolit čtení „jen toho, co nahrála tahle aplikace“.
- **Automatické odesílání** – u události zapneš jeden přepínač, vybereš Google účet a album
  (nepovinné). Všechny fotky z těch dní se samy pošlou do Google Fotek do toho alba.
- **Fronta** – vybrané fotky → Poslat → účet. Odešlou se samy na Wi-Fi (např. až budeš doma).
- **Záložka Odesílání** – fronta, události, Google účty a ruční cíle na jednom místě.
- **Oblíbené** – používá systémové oblíbené, takže je vidí i Google Fotky.
- **Alba** – složky v telefonu (Fotoaparát, Snímky obrazovky…) + vlastní alba.
- **Výběr více fotek** – podrž fotku a táhni prstem přes další (u okraje se seznam sám posouvá),
  nebo pak klepáním přidávej. Klepnutím na den či měsíc vybereš celý, ikonou v albu celé album.
- **Poslat do cloudu** – vybrané fotky dá do fronty pro zvolený Google účet (osobní, společný…)
  a album; odešlou se samy do Google Fotek.
- **Upravit / Hledat** – otevře Google Fotky.
- **Videa** – přehrávají se přímo v aplikaci (klepnutí = pauza, posuvník dole).
- **Informace o fotce** – datum, rozlišení, fotoaparát, expozice, poloha (tlačítko „Na mapě“).
- **Přesunout do složky** – mezi složkami v DCIM/ a Pictures/ nebo do nové složky.
- **Velikost dlaždic** – roztažením dvou prstů v mřížce fotky zvětšíš, stažením zmenšíš (1–7 sloupců).
- **Kalendář fotek** – tři úrovně (tlačítka nahoře nebo roztažení prstů):
  **Rok** (12 malých měsíců, klepnutím na měsíc se přiblíží), **Měsíc** (kalendář, rušné dny
  ukazují 4 fotky) a **Dny** (každý den jeden řádek fotek; velikost jako v galerii, roztažením prstů,
  další fotky dne jsou schované pod „+N“ – klepnutím otevřeš celý den; vpravo rychlé posouvání).
- **Rychlé posouvání** – po posunutí se u pravého okraje objeví táhlo s měsícem.
- **Obnovení** – stáhni seznam dolů.
- Po zavření fotky zůstaneš na stejném místě v mřížce, otočení telefonu nic nezruší.

### Staré fotky z Google Fotek

*Nastavení → Fotky z Google Fotek → Načíst z účtu.* Otevře se výběr v Google Fotkách
(Google dovolí najednou max **2 000** fotek a aplikaci nepustí do celé knihovny sám).

1. Vyber fotky (nejrychleji podržet první a táhnout) → *Hotovo*.
2. Aplikace stáhne malé náhledy (cca 10–20 kB na fotku) a výběr **otevře znovu sám**.
   V Nastavení vidíš, od kterého data pokračovat do minulosti.
3. Už stažené fotky můžeš vybrat znovu, přeskočí se. Když kolo nic nového nepřinese,
   načítání skončí (nebo *Ukončit načítání*).

- Jen přes **Wi-Fi**, na mobilních datech se nic nestáhne.
- Náhledy jsou pak vidět i offline v mřížce a kalendáři, s ikonou mráčku. Fotky, které máš
  i v telefonu, se neukážou dvakrát.
- Náhledy nejdou vybrat, poslat ani smazat. Plnou kvalitu otevřeš v Google Fotkách.
- Vypnout zobrazení: *Ukazovat v galerii*.

### Moje alba

Alba si pamatují fotky podle jména souboru a data, takže přežijí i přesun fotky.
Na nový telefon je přeneseš přes *Nastavení → Uložit alba do souboru* a pak *Načíst alba ze souboru*.

## Instalace na telefon (krok za krokem)

1. Na GitHubu otevři záložku **Actions** → poslední běh **Build APK**.
2. Dole v sekci *Artifacts* stáhni `galerie-apk` a rozbal ZIP.
3. Soubor `app-release.apk` pošli do telefonu a otevři ho.
4. Povol instalaci z neznámých zdrojů, když se telefon zeptá.
5. Při prvním spuštění povol přístup k fotkám a videím.

Nová verze se nainstaluje přes starou, alba a nastavení zůstanou (APK je vždy podepsané stejným klíčem `app/debug.keystore`).

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
   Pro načtení starých fotek z cloudu povol stejně i **Photos Picker API**.
3. *APIs & Services → OAuth consent screen* (Google Auth Platform):
   typ **External**, vyplň název a svůj e-mail, ulož.
4. V části **Audience / Test users** přidej **všechny účty**, do kterých chceš posílat
   (osobní i společný, např. SharedXXO).
5. *Credentials → Create credentials → OAuth client ID* → typ **Android**:
   - Package name: `com.justsomepren.gallery`
   - SHA-1: `9A:F4:44:88:64:64:4E:C4:E8:AA:65:24:A9:59:BA:22:38:CC:E4:B7`
6. V aplikaci: *Odesílání → Google účty → +* → vyber účet → potvrď.

Pokud Google po týdnu chce přihlášení znovu (u projektů v testovacím režimu se to může stát),
v Odesílání se u účtu objeví „Přihlásit“.

### Jak se odesílá

- Jen na **Wi-Fi** (mobilní data jde zapnout v Odesílání).
- Pod hranicí baterie (výchozí 70 %) **jen při nabíjení**.
- Tlačítko **Odeslat hned** pošle frontu okamžitě i přes data a bez ohledu na baterii.
- Každá fotka se do stejného účtu pošle jen jednou.
- Do cloudu jdou **jen fotky a videa z fotoaparátu** (složka `DCIM/Camera`). Snímky obrazovky,
  stažené obrázky ani fotky z chatů se nepošlou nikdy, ani když je vybereš ručně.
  Obecné „Sdílet“ (např. do WhatsAppu) toto omezení nemá.
- Album se v Google Fotkách vytvoří samo. Aplikace smí přidávat jen do alb, která sama vytvořila.

## Ruční odesílání přes aplikace

Hlavní cesta je fronta do Google Fotek (výběr účtu i alba přímo v aplikaci).
Ruční cíle jsou navíc: otevřou jinou aplikaci s vybranými fotkami. Google Fotky takto nahrají
do účtu, který je v nich právě aktivní.

Pokud chceš potvrzovat oblíbené bez dialogu, povol *Nastavení → Oblíbené bez potvrzování*.

## Data třetích stran

Názvy měst a zemí pro hledání výletů: [GeoNames](https://www.geonames.org/) (cities15000),
licence [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
