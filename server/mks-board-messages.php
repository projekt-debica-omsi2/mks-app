<?php
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');

$cacheDir = __DIR__ . DIRECTORY_SEPARATOR . 'messenger-data';
$cacheFile = $cacheDir . DIRECTORY_SEPARATOR . 'mks-board-messages-cache.json';
$freshTtl = 300;
$staleTtl = 86400;
if (!is_dir($cacheDir)) @mkdir($cacheDir, 0775, true);

function out(array $payload): void {
    echo json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
    exit;
}
function clean_text(string $text): string {
    $text = html_entity_decode($text, ENT_QUOTES | ENT_HTML5, 'UTF-8');
    $text = strip_tags($text);
    $text = preg_replace('/\s+/u', ' ', $text) ?? $text;
    return trim($text);
}
function read_cache(string $path, int $maxAge): ?array {
    if (!is_file($path) || time() - (int)filemtime($path) > $maxAge) return null;
    $raw = @file_get_contents($path);
    $json = $raw !== false ? json_decode($raw, true) : null;
    return is_array($json) ? $json : null;
}
function fetch_json(string $url, int $timeout = 4): ?array {
    if (!function_exists('curl_init')) return null;
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER=>true, CURLOPT_FOLLOWLOCATION=>true, CURLOPT_MAXREDIRS=>3,
        CURLOPT_CONNECTTIMEOUT=>2, CURLOPT_TIMEOUT=>$timeout, CURLOPT_ENCODING=>'',
        CURLOPT_USERAGENT=>'E-Debica timetable/1.0',
        CURLOPT_HTTPHEADER=>['Accept: application/json','Connection: close'],
        CURLOPT_SSL_VERIFYPEER=>true, CURLOPT_SSL_VERIFYHOST=>2,
    ]);
    $body = (string)curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    if ($code < 200 || $code >= 300 || trim($body) === '') return null;
    $json = json_decode($body, true);
    return is_array($json) ? $json : null;
}

$fresh = read_cache($cacheFile, $freshTtl);
if ($fresh) { $fresh['cache'] = 'fresh'; out($fresh); }

// Używamy oficjalnego, publicznego kanału komunikatów MKS. Nie skanujemy już
// dziesiątek nieudokumentowanych endpointów myBus, bo mogło to blokować PHP i rozkłady.
$posts = fetch_json('https://mks.debica.pl/wp-json/wp/v2/posts?per_page=8&_fields=date,link,title,excerpt', 4);
$messages = [];
if (is_array($posts)) {
    foreach ($posts as $post) {
        if (!is_array($post)) continue;
        $title = clean_text((string)($post['title']['rendered'] ?? ''));
        $excerpt = clean_text((string)($post['excerpt']['rendered'] ?? ''));
        if ($title === '') continue;
        $text = $title;
        if ($excerpt !== '' && strtolower($excerpt) !== strtolower($title)) $text .= ' — ' . $excerpt;
        $messages[] = ['text'=>$text, 'url'=>(string)($post['link'] ?? ''), 'date'=>(string)($post['date'] ?? '')];
        if (count($messages) >= 8) break;
    }
}

if ($messages) {
    $payload = [
        'ok'=>true, 'messages'=>$messages, 'source'=>'Komunikaty MKS Dębica',
        'source_url'=>'https://mks.debica.pl/aktualnosci/', 'live'=>false,
        'fetched_at'=>gmdate('c'), 'cache'=>'miss'
    ];
    @file_put_contents($cacheFile, json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT), LOCK_EX);
    out($payload);
}

$stale = read_cache($cacheFile, $staleTtl);
if ($stale) { $stale['cache'] = 'stale'; out($stale); }

out([
    'ok'=>true, 'messages'=>[], 'source'=>'MKS Dębica',
    'source_url'=>'https://mks.debica.pl/aktualnosci/', 'live'=>false,
    'fetched_at'=>gmdate('c'), 'cache'=>'none'
]);
