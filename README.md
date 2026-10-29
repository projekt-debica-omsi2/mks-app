# MKS Dębica 2.2.1 — naprawa GPS na Androidzie

**Poprawka błędu z telefonu:** `http://apache.org/xml/features/disallow-doctype-decl`.
W wersji 2.2 silnik XML Androida mógł zgłaszać ten adres jako błąd podczas przetwarzania odpowiedzi `PingService` i `GetVehicles`. Poprawiony `GpsXml.java` nie ustawia nieobsługiwanej opcji SAX/Xerces, odrzuca niebezpieczne deklaracje DTD/encje i blokuje wczytywanie zasobów zewnętrznych. Ten konkretny błąd parsera nie powinien się już pojawiać.

Znaczniki przystanków na mapie to teraz małe białe kropki z zieloną obwódką, **nie niebieskie ikonki autobusów**. Prawdziwe autobusy otrzymują zielone znaczniki z numerem linii dopiero po otrzymaniu współrzędnych z API.

Pod `scripts/GpsXmlSmokeTest.java` jest test parsera uruchamiany podczas GitHub Actions; testuje odpowiedzi PingService, GetVehicles i odrzucanie DTD.

**Ważne:** poprawka usuwa problem konfiguracji parsera XML; faktyczna dostępność API MKS i pojawienie się autobusów wymagają sprawdzenia po instalacji na telefonie. Rozkłady nadal są kopią offline i mogą nie odzwierciedlać ostatnich zmian.

Instrukcja: rozpakuj ZIP, zastąp pliki repozytorium (pozostaw ukryty `.git`), wykonaj `Commit to main` i `Push origin`. Gdy GitHub Actions skończy się na zielono, pobierz artefakt `MKS-Debica-APK`. Jeśli przy instalacji aktualizacji Android zgłasza konflikt podpisu, usuń wcześniejszą wersję (utracisz zapisane lokalnie ulubione) i zainstaluj nową.

---

# MKS Dębica 2.2.0 — ikony z APK myBus

Wersja projektu z włączonymi ikonami wyodrębnionymi z `mybus.apk` przekazanego przez użytkownika. Ikony są użyte w nawigacji, kartach przystanków, mapie i ustawieniach. **Grafiki nie zapewniają dostępu do GPS**; działanie bezpośredniego API MKS pozostaje do testu na telefonie.

Dokładna lista ikon i uwagi o prawach do grafik: [`ASSETY_Z_MYBUS.md`](ASSETY_Z_MYBUS.md).

# MKS Dębica 2.1 — diagnostyka GPS i jasne źródła danych — kompletny projekt Android do GitHub Actions

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

## Weryfikacja danych w wersji 2.1

**Wbudowane godziny odjazdów nie są automatycznie aktualizowane**. To kopia danych użytkownika. Na każdej tabliczce znajduje się link do oficjalnej strony, a na ekranach przystanków i linii znajdują się linki do operatora MKS. Nie opisuj lokalnej kopii jako rozkładu na żywo.

**Pozycje autobusów GPS**: aplikacja najpierw wykonuje PingService, potem GetVehicles na oryginalnym API MKS. Nowy przycisk „Sprawdź połączenie GPS” pozwala rozróżnić niedostępność samego serwera od braku zwróconych pozycji pojazdów. Wynik PingService nie jest dowodem, że GetVehicles zwróci autobusy. Bez działającego API nie można zagwarantować pozycji w aplikacji.

Oficjalny myBus dla MKS Dębica: https://mks.debica.pl/dla-pasazera/aplikacja-mybus/
Rozkłady operatora: http://rj.mks.debica.pl/

Podczas testu APK w telefonie otwórz GPS → „Sprawdź połączenie GPS”, a następnie „Odśwież GPS”. Zanotuj oba komunikaty. W razie problemów połączenia z serwerem, jego odpowiedź musi zostać zdiagnozowana z sieci telefonu; żaden lokalny test projektu nie może potwierdzić niezależnie działania operatora.

Wersja 2.1 poprawia również pokazywanie nieznanego opóźnienia: wcześniej brak pola `o` był pokazywany jako 0 minut, co mogło być nieprawdą.

Geolokalizacja telefonu: gdy Android nie uzyska współrzędnych przez 15 sekund, aplikacja pokaże konkretny komunikat zamiast czekać bez końca. Linki do oficjalnych tabliczek otwierają zewnętrzną przeglądarkę.
