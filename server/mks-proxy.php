<?php
declare(strict_types=1);

header('Content-Type: text/xml; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');

$cacheDir = __DIR__ . DIRECTORY_SEPARATOR . 'messenger-data';
$cacheFile = $cacheDir . DIRECTORY_SEPARATOR . 'mks-live-cache.xml';
$metaFile = $cacheDir . DIRECTORY_SEPARATOR . 'mks-live-cache.json';
$lockFile = $cacheDir . DIRECTORY_SEPARATOR . 'mks-live-cache.lock';
$freshSeconds = 8;
$staleSeconds = 21600;
if (!is_dir($cacheDir)) @mkdir($cacheDir, 0775, true);

function valid_payload(string $body): bool {
    $body = trim($body);
    if ($body === '' || strlen($body) < 20) return false;
    if (stripos($body, '<p') === false || strpos($body, '[') === false) return false;
    $lower = strtolower($body);
    foreach (['internal server error','bad gateway','service unavailable','an error occurred'] as $bad) {
        if (strpos($lower, $bad) !== false) return false;
    }
    return true;
}
function read_meta(string $path): array {
    if (!is_file($path)) return [];
    $raw = @file_get_contents($path);
    $json = $raw !== false ? json_decode($raw, true) : null;
    return is_array($json) ? $json : [];
}
function serve_cache(string $cacheFile, string $metaFile, string $mode): bool {
    if (!is_file($cacheFile)) return false;
    $body = @file_get_contents($cacheFile);
    if ($body === false || !valid_payload($body)) return false;
    $meta = read_meta($metaFile);
    header('X-MKS-Cache: ' . $mode);
    if (!empty($meta['saved_at'])) header('X-MKS-Saved-At: ' . (string)$meta['saved_at']);
    if (!empty($meta['source'])) header('X-MKS-Source: ' . rawurlencode((string)$meta['source']));
    echo $body;
    return true;
}
function resolve_host_fallback(string $host): array {
    $ips = [];
    if (function_exists('dns_get_record')) {
        $records = @dns_get_record($host, DNS_A);
        if (is_array($records)) foreach ($records as $record) {
            if (!empty($record['ip']) && filter_var($record['ip'], FILTER_VALIDATE_IP, FILTER_FLAG_IPV4)) $ips[] = $record['ip'];
        }
    }
    if (!$ips && function_exists('curl_init')) {
        foreach ([
            'https://dns.google/resolve?name=' . rawurlencode($host) . '&type=A',
            'https://cloudflare-dns.com/dns-query?name=' . rawurlencode($host) . '&type=A'
        ] as $url) {
            $ch = curl_init($url);
            curl_setopt_array($ch, [
                CURLOPT_RETURNTRANSFER=>true, CURLOPT_CONNECTTIMEOUT=>2, CURLOPT_TIMEOUT=>4,
                CURLOPT_HTTPHEADER=>['Accept: application/dns-json','Connection: close'],
                CURLOPT_USERAGENT=>'E-Debica MKS proxy/2.0', CURLOPT_SSL_VERIFYPEER=>true,
                CURLOPT_SSL_VERIFYHOST=>2
            ]);
            $body = (string)curl_exec($ch);
            $code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
            curl_close($ch);
            if ($code < 200 || $code >= 300 || $body === '') continue;
            $json = json_decode($body, true);
            foreach (($json['Answer'] ?? []) as $answer) {
                $ip = (string)($answer['data'] ?? '');
                if (filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4)) $ips[] = $ip;
            }
            if ($ips) break;
        }
    }
    return array_values(array_unique($ips));
}
function curl_get_page(string $url, array $headers, array $resolve, int $timeout, bool $cookieEngine = true): array {
    $ch = curl_init($url);
    $options = [
        CURLOPT_RETURNTRANSFER=>true, CURLOPT_FOLLOWLOCATION=>true, CURLOPT_MAXREDIRS=>3,
        CURLOPT_CONNECTTIMEOUT=>3, CURLOPT_TIMEOUT=>$timeout, CURLOPT_ENCODING=>'',
        CURLOPT_IPRESOLVE=>CURL_IPRESOLVE_V4,
        CURLOPT_USERAGENT=>'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36',
        CURLOPT_HTTPHEADER=>$headers, CURLOPT_SSL_VERIFYPEER=>true, CURLOPT_SSL_VERIFYHOST=>2,
    ];
    if ($resolve) $options[CURLOPT_RESOLVE] = $resolve;
    if ($cookieEngine) $options[CURLOPT_COOKIEFILE] = '';
    curl_setopt_array($ch, $options);
    $body = (string)curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    $effective = (string)curl_getinfo($ch, CURLINFO_EFFECTIVE_URL);
    $error = curl_error($ch);
    curl_close($ch);
    return ['body'=>$body,'code'=>$code,'url'=>$effective ?: $url,'error'=>$error];
}
function fetch_live(): array {
    if (!function_exists('curl_init')) return ['body'=>null,'source'=>null,'errors'=>['Brak cURL w PHP.']];
    $host = 'rj.mks.debica.pl';
    $ips = resolve_host_fallback($host);
    $errors = [];

    foreach (['https','http'] as $scheme) {
        $port = $scheme === 'https' ? 443 : 80;
        $resolve = [];
        foreach ($ips as $ip) $resolve[] = $host . ':' . $port . ':' . $ip;
        $base = $scheme . '://' . $host;

        // Krótka inicjalizacja sesji/cookies, jak w przeglądarce myBus.
        curl_get_page($base . '/', ['Accept: text/html,*/*','Connection: close'], $resolve, 5);

        foreach (['/Routes/CNR_GetVehicles?r=&d=&nb=', '/Home/CNR_GetVehicles?r=&d=&nb='] as $path) {
            $url = $base . $path;
            $response = curl_get_page($url, [
                'Accept: application/xml, text/xml, */*; q=0.01',
                'Accept-Language: pl-PL,pl;q=0.9,en;q=0.6',
                'X-Requested-With: XMLHttpRequest',
                'Referer: ' . $base . '/',
                'Connection: close'
            ], $resolve, 7);
            if ($response['code'] >= 200 && $response['code'] < 300 && valid_payload($response['body'])) {
                return ['body'=>$response['body'],'source'=>$response['url'],'errors'=>$errors];
            }
            $errors[] = $url . ' => HTTP ' . $response['code'] . ($response['error'] !== '' ? ' / ' . $response['error'] : '');
        }
    }
    return ['body'=>null,'source'=>null,'errors'=>$errors];
}

$cacheAge = is_file($cacheFile) ? max(0, time() - (int)filemtime($cacheFile)) : PHP_INT_MAX;
if ($cacheAge <= $freshSeconds && serve_cache($cacheFile, $metaFile, 'FRESH')) exit;

$lock = @fopen($lockFile, 'c+');
if ($lock !== false && !@flock($lock, LOCK_EX | LOCK_NB)) {
    if (serve_cache($cacheFile, $metaFile, $cacheAge > $freshSeconds ? 'STALE' : 'FRESH')) { fclose($lock); exit; }
}

$result = fetch_live();
if (is_string($result['body']) && $result['body'] !== '') {
    $now = gmdate('c');
    @file_put_contents($cacheFile, $result['body'], LOCK_EX);
    @file_put_contents($metaFile, json_encode([
        'saved_at'=>$now, 'source'=>$result['source'], 'errors'=>$result['errors']
    ], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT), LOCK_EX);
    header('X-MKS-Cache: MISS');
    header('X-MKS-Saved-At: ' . $now);
    if ($result['source']) header('X-MKS-Source: ' . rawurlencode((string)$result['source']));
    echo $result['body'];
    if ($lock !== false) { @flock($lock, LOCK_UN); fclose($lock); }
    exit;
}

if ($cacheAge <= $staleSeconds && serve_cache($cacheFile, $metaFile, 'STALE')) {
    if ($lock !== false) { @flock($lock, LOCK_UN); fclose($lock); }
    exit;
}
if ($lock !== false) { @flock($lock, LOCK_UN); fclose($lock); }

http_response_code(503);
header('Content-Type: text/plain; charset=utf-8');
header('X-MKS-Cache: NONE');
echo "MKS_LIVE_UNAVAILABLE\n";
echo "GPS chwilowo niedostępny. Lokalne rozkłady i tablica przystankowa działają niezależnie.";
