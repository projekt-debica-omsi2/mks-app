# MKS Dębica 2.0 — kompletny projekt Android do GitHub Actions

To **nieoficjalna** aplikacja pasażerska. To pełny projekt Android, nie gotowe APK.

## Najprościej: wymiana całego projektu przez GitHub Desktop

1. Rozpakuj pobrany ZIP `MKS-Debica-KOMPLET-GitHub.zip`.
2. W GitHub Desktop wybierz repozytorium `projekt-debica-omsi2/mks-app`, potem **Repository → Show in Explorer**.
3. **Nie usuwaj ukrytego folderu `.git`!** Z katalogu repozytorium usuń stare pliki projektu, ALE pozostaw `.git`.
4. Skopiuj do repozytorium **całą zawartość** rozpakowanego folderu `MKS-Debica-KOMPLET-GitHub`, włącznie z ukrytym `.github`.
5. W GitHub Desktop: wpisz nazwę zmiany, **Commit to main**, a potem **Push origin**.
6. Otwórz https://github.com/projekt-debica-omsi2/mks-app/actions. Wybierz **Zbuduj APK MKS Debica**. Budowanie wystartuje po `Push origin`, można też użyć **Run workflow**.
7. Jeżeli zadanie skończy się zielonym znakiem, w jego części **Artifacts** pobierz **MKS-Debica-APK**. Po rozpakowaniu otrzymasz `app-debug.apk` do prywatnego testu na Androidzie.

**Ważne:** Nie wgrywaj ZIP-a jako jednego pliku do repozytorium. GitHub musi zobaczyć pliki projektu, w tym `.github/workflows/android-apk.yml` i `app/` w katalogu głównym.

## Funkcje

- **374 przystanki**, wyszukiwanie numerów i nazw, najbliższe przystanki przez lokalizację telefonu.
- **14 linii**, kierunki oraz lista przystanków *alfabetycznie* (brak wiarygodnej sekwencji przejazdu w danych).
- **812 tabliczek odjazdów** w czterech wariantach: roboczy, roboczy wakacyjny/feryjny, sobota, niedziele/święta. Wszystkie działają offline.
- **GPS autobusów**: natywne żądania Android do `rj.mks.debica.pl/AndroidService/SchedulesService.svc` z `PingService` / `GetVehicles` i nagłówkiem `Age`. Najpierw bezpośrednio, potem opcjonalne ręcznie podane proxy. Dane są odświeżane co 25 s, gdy jest otwarty widok GPS.
- **Mapa**: kafelki OpenStreetMap pobierane przez internet; jeśli brak internetu, podkład ulic może być niewidoczny. Stosuj zasady OpenStreetMap tile usage policy, zwłaszcza jeśli aplikacja miałaby być masowo publikowana.
- **Przesiadki**: jedynie **orientacyjne** połączenia na podstawie linii wspólnych dla przystanków. Nie ma gwarancji trasy, kierunku, czasu przejazdu ani zgodności kursów.
- Ulubione, udostępnianie godzin, zapasowe proxy w ustawieniach (domyślnie puste).

## Co jest zweryfikowane, a co nie

Sprawdzane są spójność 374/14/812 rekordów oraz składnia JavaScript i struktura konfiguracji Android/GitHub Actions przez `node scripts/check-data.js && node scripts/check-project.js`.

Nie twierdzimy, że prawdziwe pozycje GPS już działają: dostępność oryginalnego API myBus **nie została potwierdzona na telefonie** i zależy od serwera MKS. Starszy endpoint działa przez nieszyfrowany HTTP; przy udostępnianiu aplikacji szerszej grupie warto przejść na HTTPS. Dane rozkładów pochodzą z dostarczonej kopii i nie aktualizują się automatycznie.

Projekt zawiera autorską zielono-żółtą ikonę autobusu. **Nie jest to oficjalny znak MKS**.

W tym środowisku nie ma lokalnego Android SDK i Gradle, więc rzeczywista kompilacja APK nie została wykonana lokalnie. Sprawdzi ją GitHub Actions po wrzuceniu projektu. Jeśli pojawi się inny błąd, jego log z zakładki Actions pokaże dokładny następny problem.

## Parametry techniczne

- `applicationId`: `pl.debica.mks.app`, `versionName`: `2.0.0`, `versionCode`: `4`
- Android 7.0+, Java 17, Android Gradle Plugin 8.7.3, Gradle 8.10.2, SDK 35.
- GitHub Actions: `android-actions/setup-android@v4` z `packages: platform-tools`, bez wycofanego pakietu `tools`.
- Dane źródłowe w `data/`; zasoby do uruchomienia w `app/src/main/assets/`.
- APK debug służy do testów i jest podpisywany kluczem debug GitHub Runnera; nie jest to podpisany pakiet do Google Play.
