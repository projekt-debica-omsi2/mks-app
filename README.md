# MKS Dębica — aplikacja Android APK

Nieoficjalna aplikacja pasażerska na Androida, przygotowana na podstawie przesłanych plików. **Nie wymaga Android Studio ani własnego komputera do budowania:** GitHub Actions automatycznie kompiluje APK.

## Jak zbudować APK na GitHubie

1. Utwórz **nowe repozytorium** na github.com (np. `MKS-Debica`).
2. Rozpakuj `MKS-Debica-Android.zip` i prześlij **całą zawartość folderu** do katalogu głównego repozytorium, łącznie z ukrytym katalogiem `.github`. Możesz użyć przycisku **Add file → Upload files**. Commit do gałęzi `main`.
3. Otwórz **Actions → Zbuduj APK MKS Debica**. Po pierwszym przesłaniu workflow uruchomi się na `main` samodzielnie; można też kliknąć **Run workflow**.
4. Po zielonym zakończeniu zadania otwórz jego stronę, na dole pobierz artefakt **MKS-Debica-APK**, rozpakuj go i zainstaluj **`app-debug.apk`** na telefonie.
5. Telefon może poprosić o zezwolenie na instalację aplikacji z tego źródła. Jest to **APK debug do instalacji prywatnej/testowej**, a nie podpisana produkcyjna paczka do Google Play.

W katalogu głównym znajdują się `settings.gradle`, `build.gradle`, `gradle.properties` oraz `app/`. Workflow jest w `.github/workflows/android-apk.yml`. Używa Gradle 8.10.2, JDK 17 i Android SDK 35; **nie potrzebuje lokalnego wrappera Gradle**.

## Funkcje

- **GPS autobusów**: pobieranie aktualnych pozycji z oryginalnego API myBus MKS Dębica; opcjonalny zapasowy pośrednik PHP; odświeżanie co 25 sekund, gdy ekran GPS jest aktywny; numer linii, numer pojazdu, kierunek (gdy API zwróci), opóźnienie; informacja o nieaktualnych danych w przypadku błędu.
- **Mapa**: kafelki OpenStreetMap, przeciąganie, przybliżanie, filtry linii, pojazdy i na większym przybliżeniu przystanki. Kafelki wymagają internetu.
- **374 przystanki**: szukanie po nazwie i numerze, opcjonalna lokalizacja telefonu / najbliższe, ulubione.
- **14 linii**: wyszukiwanie linii i kierunku, przystanki w każdym kierunku (porządek alfabetyczny, a nie trasa przejazdu).
- **812 tabliczek**: wszystkie zapisane godziny, podział na robocze, robocze wakacyjne/feryjne, soboty i niedziele/święta; link do oryginalnej tabliczki; udostępnianie. Dane rozkładowe działają offline.
- **Przesiadki**: wskazanie wspólnej linii lub przystanku przesiadkowego na podstawie wspólnych grup linia/kierunek; **wyniki orientacyjne**, bez weryfikacji kolejności przejazdu, czasu jazdy ani zgodności godzin konkretnych kursów. To nie jest pełny planner tras GTFS.

## GPS bezpośrednio z MKS — bez własnego serwera (v1.1.0)

**Nie trzeba mieć działającego `e-dębica.pl:8088` ani żadnego proxy.** APK łączy się bezpośrednio z oryginalnym API MKS Dębica, ustawionym na stałe w aplikacji:

`http://rj.mks.debica.pl/AndroidService/SchedulesService.svc/`

Natywny kod Android (Java, `HttpURLConnection`) odpytuje `PingService`, pobiera wartość potrzebną do nagłówka `Age`, a następnie wykonuje `GetVehicles`. Żądania wysyłane są z telefonu, nie przez WebView, więc nie wymagają CORS. Pozycje pochodzą wyłącznie z odpowiedzi serwera; aplikacja nie generuje fałszywych danych.

Odświeżanie GPS następuje co 25 sekund, gdy otwarty jest ekran mapy. Jeśli serwer MKS nie odpowiada, pokazywany jest błąd; poprzednia pozycja jest oznaczana jako nieaktualna. **Dostępność zewnętrznego API MKS nie została potwierdzona na żywym urządzeniu.** Starsze API korzysta z HTTP i może być niedostępne z niektórych sieci lub ograniczone po stronie operatora.

W ustawieniach jest opcjonalne pole **Zapasowy proxy PHP** (domyślnie puste). Jeśli właściciel używa własnego proxy i poda jego adres, aplikacja spróbuje połączenia zapasowego **dopiero po błędzie połączenia bezpośredniego**. Jeżeli własny serwer jest wyłączony, jego brak nie wpływa na działanie rozkładów, mapy i wyszukiwarki. Pierwszy start świeżo zainstalowanej aplikacji nie używa e-dębica.pl:8088. Jeśli aktualizujesz starszą wersję, jej domyślny adres 8088 jest automatycznie ignorowany; inne świadomie wpisane adresy zachowano.

Dołączony folder `server/` jest tylko opcją dla kogoś, kto chce wdrożyć własny proxy, i nie jest potrzebny do zbudowania APK.

## Ważne ograniczenia

- Zapisane tabliczki są **kopią danych z przesłanych plików** i nie aktualizują się automatycznie. Oznaczenia kursów były puste w przesłanej kopii. W dzień świąteczny i ferie wybierz ręcznie właściwy typ rozkładu (aplikacja rozpoznaje tylko dzień tygodnia).
- Przesłana baza nie zawiera poprawnej sekwencji wszystkich kursów i ich czasów przejazdu pomiędzy przystankami, dlatego propozycje przesiadek nie mogą być traktowane jako gwarantowane połączenia.
- Aktualna pozycja telefonu służy wyłącznie lokalnie do obliczenia odległości do przystanków — aplikacja nie wysyła jej do proxy.
- Aplikacja posiada autorską ikonę zielono-żółtego autobusu z napisem MKS DĘBICA. **Nie jest to zweryfikowany oficjalny logotyp operatora**. Źródło: `icon-source.png`; można zastąpić pliki `res/mipmap-*/ic_launcher*.png` po uzyskaniu właściwego logo i zgody na jego użycie.
- Na potrzeby bezpośredniego połączenia ze starszym, nieszyfrowanym API MKS w manifeście włączono ruch HTTP. Zalecany wariant produkcyjny to własne proxy **HTTPS** i usunięcie bezpośredniego HTTP.

## Uruchomienie lokalne / modyfikacje

Do lokalnej kompilacji potrzeba JDK 17, Android SDK z platformą 35 i Gradle 8.10.2:

```bash
gradle :app:assembleDebug
```

Do modyfikacji interfejsu: `app/src/main/assets/index.html`, `style.css`, `app.js`. Dane rozkładów: `data.js` (generowany z plików użytkownika). Natywna obsługa HTTP/GPS: `app/src/main/java/pl/debica/mks/app/MainActivity.java`.

Kontrola danych przed kompilacją: `node scripts/check-data.js`.

