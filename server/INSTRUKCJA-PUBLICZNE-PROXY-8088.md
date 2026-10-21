# PUBLICZNE PROXY MKS Dębica — e-dębica.pl:8088

Ten plik jest dla właściciela serwera `e-dębica.pl`.

## Co robi nowa wersja

- Jeden plik PHP: `mks-api-bridge.php`.
- **Każdy adres IP** może wykonać żądanie. Nie ma logowania, białej listy IP ani blokady sieci komórkowych.
- Dla aplikacji Android domyślny adres katalogu proxy to `http://e-dębica.pl:8088`.
- CORS `Access-Control-Allow-Origin: *` pozwala też na dostęp z innych domen w przeglądarce.
- Nagłówek `Age` jest opcjonalny — PHP wylicza go sam, jeśli klient go nie poda.
- Jedynie *cel przekazywanego żądania* jest stały (`rj.mks.debica.pl`): brak możliwości ustawienia innego docelowego serwera. To **nie jest ograniczenie IP użytkowników**.

## Instalacja na istniejącym serwerze PHP

1. **Zrób kopię** poprzedniego `mks-api-bridge.php`.
2. Skopiuj **nowy `mks-api-bridge.php`** do katalogu WWW obsługiwanego pod adresem `e-dębica.pl:8088` (np. katalog główny tej witryny). Nie wgrywaj `mks-brygady-api.php` — zawiera hasło administratora.
3. Witryna WWW musi nasłuchiwać na porcie **8088**, na wszystkich interfejsach (`0.0.0.0` lub „All Unassigned”). Nie ustawiaj `127.0.0.1`, bo działa tylko lokalnie.
4. W zaporze systemu otwórz **TCP 8088 przychodzące**, a jeśli witryna stoi za routerem/NAT, przekieruj zewnętrzny TCP 8088 na właściwy komputer i port. DNS domeny musi wskazywać na osiągalny publiczny IP (lub na odpowiedni reverse proxy).
5. PHP musi mieć możliwość wychodzącego TCP 80 do `rj.mks.debica.pl`, a PHP `stream_socket_client()` musi być dostępne.
6. Nie musisz nic zmieniać w uprawnieniach aplikacji Android — nowa wersja APK używa tego adresu domyślnie.

## Test z **innego IP** (na przykład LTE zamiast Wi-Fi)

Po wdrożeniu otwórz kolejno:

- `http://e-dębica.pl:8088/mks-api-bridge.php?method=Health` — JSON `"ok":true` oznacza, że sam serwer PHP odpowiada.
- `http://e-dębica.pl:8088/mks-api-bridge.php?method=PingService` — odpowiedź XML potwierdza połączenie do serwera MKS.
- `http://e-dębica.pl:8088/mks-api-bridge.php?method=GetVehicles` — XML z pozycji, gdy usługa GPS MKS udostępnia pojazdy.

W pasku adresu przeglądarki można użyć też postaci ASCII domeny: `xn--e-dbica-c9a.pl`.

Jeśli **Health działa tylko w sieci domowej**, a nie przez LTE, problem jest w ustawieniach firewalla, NAT, DNS lub wiązania portu, a nie w samym PHP.

Jeśli **Health działa z LTE**, ale PingService nie, sprawdź dostęp serwera WWW do `rj.mks.debica.pl:80` oraz czy oryginalne API MKS odpowiada.

## Przykład reguły zapory Windows (PowerShell uruchomiony jako administrator)

```powershell
New-NetFirewallRule -DisplayName "MKS proxy 8088 TCP" -Direction Inbound -Protocol TCP -LocalPort 8088 -Action Allow
```

IIS: w *Bindings* witryny ustaw `http`, **All Unassigned**, port `8088`. W przypadku IIS/FastCGI upewnij się, że plik `.php` jest obsługiwany przez PHP, nie serwowany jako kod źródłowy.

Apache: użyj `Listen 0.0.0.0:8088` i w odpowiednim VirtualHost udziel zwykłego dostępu do katalogu (`Require all granted`); konfiguracja nie zawiera `Require ip`.

**Uwaga:** port 8088 musi być osiągalny publicznie; sam PHP nie potrafi odblokować routera i zapory. Wersja pod `http://` jest nieszyfrowana — do publicznego użycia docelowo warto włączyć HTTPS na porcie 8088 (lub na 443 przez reverse proxy) i zmienić adres w ustawieniach aplikacji. Nie wystawiaj paneli administracyjnych, haseł i plików bazy w tym samym publicznym katalogu.
