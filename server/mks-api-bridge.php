<?php
declare(strict_types=1);

/*
 * Most do oryginalnego API MKS Dębica bez rozszerzenia cURL.
 * Używa wyłącznie funkcji stream_socket_client(), dostępnej w rdzeniu PHP.
 * PUBLICZNY, bez ograniczeń IP/Origin, bez loginu. Host docelowy jest stały:
 * rj.mks.debica.pl, aby endpoint nie był otwartym proxy do dowolnych stron.
 * Adres zewnętrzny (gdy wgrany do katalogu głównego strony):
 * http://e-dębica.pl:8088/mks-api-bridge.php?method=PingService
 */

header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, OPTIONS');
header('Access-Control-Allow-Headers: Age, Accept, Content-Type');
header('Access-Control-Expose-Headers: X-MKS-Age-Received, X-MKS-Age-Source, X-MKS-Upstream-Status');
header('Vary: Accept-Encoding');
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');
header('X-MKS-Bridge: PHP-SOCKET');
header('X-MKS-Upstream-User-Agent: myBusOnline');

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'OPTIONS') {
    http_response_code(204);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'GET') {
    http_response_code(405);
    header('Content-Type: text/plain; charset=utf-8');
    echo "METHOD_NOT_ALLOWED\n";
    exit;
}

/**
 * Odczyt nagłówka Age przekazanego przez przeglądarkę.
 */
function incoming_age(): string
{
    $values = [
        $_SERVER['HTTP_AGE'] ?? '',
        $_SERVER['REDIRECT_HTTP_AGE'] ?? '',
    ];

    if (function_exists('getallheaders')) {
        $headers = getallheaders();
        if (is_array($headers)) {
            foreach ($headers as $name => $value) {
                if (strcasecmp((string)$name, 'Age') === 0) {
                    $values[] = (string)$value;
                }
            }
        }
    }

    foreach ($values as $value) {
        $value = trim((string)$value);
        if ($value !== '' && preg_match('/^\d{1,10}$/', $value)) {
            return $value;
        }
    }

    return '';
}

/**
 * Dekodowanie HTTP Transfer-Encoding: chunked.
 */
function decode_chunked(string $body): string
{
    $decoded = '';
    $position = 0;
    $length = strlen($body);

    while ($position < $length) {
        $lineEnd = strpos($body, "\r\n", $position);
        if ($lineEnd === false) {
            return $body;
        }

        $sizeLine = trim(substr($body, $position, $lineEnd - $position));
        $semicolon = strpos($sizeLine, ';');
        if ($semicolon !== false) {
            $sizeLine = substr($sizeLine, 0, $semicolon);
        }

        if ($sizeLine === '' || !ctype_xdigit($sizeLine)) {
            return $body;
        }

        $size = hexdec($sizeLine);
        $position = $lineEnd + 2;

        if ($size === 0) {
            break;
        }

        if ($position + $size > $length) {
            return $body;
        }

        $decoded .= substr($body, $position, $size);
        $position += $size;

        if (substr($body, $position, 2) === "\r\n") {
            $position += 2;
        }
    }

    return $decoded;
}

/**
 * @return array{status:int, headers:array<string,string>, body:string, error:string}
 */
function http_socket_get(
    string $host,
    string $path,
    array $headers,
    int $timeout = 18
): array {
    $errno = 0;
    $errstr = '';

    $socket = @stream_socket_client(
        'tcp://' . $host . ':80',
        $errno,
        $errstr,
        6,
        STREAM_CLIENT_CONNECT
    );

    if (!is_resource($socket)) {
        return [
            'status' => 0,
            'headers' => [],
            'body' => '',
            'error' => 'CONNECT ' . $errno . ': ' . $errstr,
        ];
    }

    stream_set_timeout($socket, $timeout);

    $requestHeaders = [
        'GET ' . $path . ' HTTP/1.1',
        'Host: ' . $host,
        'User-Agent: myBusOnline',
        'Accept: application/xml, text/xml, */*; q=0.01',
        'Accept-Language: pl-PL,pl;q=0.9,en;q=0.6',
        'Accept-Encoding: identity',
        'Connection: close',
    ];

    foreach ($headers as $name => $value) {
        $requestHeaders[] = $name . ': ' . $value;
    }

    $request = implode("\r\n", $requestHeaders) . "\r\n\r\n";

    $written = 0;
    $requestLength = strlen($request);

    while ($written < $requestLength) {
        $result = @fwrite($socket, substr($request, $written));
        if ($result === false || $result === 0) {
            fclose($socket);
            return [
                'status' => 0,
                'headers' => [],
                'body' => '',
                'error' => 'WRITE_FAILED',
            ];
        }
        $written += $result;
    }

    $raw = '';
    $maxBytes = 8 * 1024 * 1024;

    while (!feof($socket) && strlen($raw) < $maxBytes) {
        $chunk = @fread($socket, 8192);

        if ($chunk === false) {
            break;
        }

        if ($chunk === '') {
            $meta = stream_get_meta_data($socket);
            if (!empty($meta['timed_out'])) {
                fclose($socket);
                return [
                    'status' => 0,
                    'headers' => [],
                    'body' => '',
                    'error' => 'READ_TIMEOUT',
                ];
            }
            continue;
        }

        $raw .= $chunk;
    }

    fclose($socket);

    if ($raw === '') {
        return [
            'status' => 0,
            'headers' => [],
            'body' => '',
            'error' => 'EMPTY_RESPONSE',
        ];
    }

    $separator = strpos($raw, "\r\n\r\n");
    if ($separator === false) {
        return [
            'status' => 0,
            'headers' => [],
            'body' => '',
            'error' => 'INVALID_HTTP_RESPONSE',
        ];
    }

    $headerText = substr($raw, 0, $separator);
    $body = substr($raw, $separator + 4);
    $headerLines = explode("\r\n", $headerText);
    $statusLine = array_shift($headerLines);

    $status = 0;
    if (is_string($statusLine)
        && preg_match('#^HTTP/\d(?:\.\d)?\s+(\d{3})#i', $statusLine, $match)
    ) {
        $status = (int)$match[1];
    }

    $responseHeaders = [];
    foreach ($headerLines as $line) {
        $colon = strpos($line, ':');
        if ($colon === false) {
            continue;
        }

        $name = strtolower(trim(substr($line, 0, $colon)));
        $value = trim(substr($line, $colon + 1));

        if (isset($responseHeaders[$name])) {
            $responseHeaders[$name] .= ', ' . $value;
        } else {
            $responseHeaders[$name] = $value;
        }
    }

    if (stripos($responseHeaders['transfer-encoding'] ?? '', 'chunked') !== false) {
        $body = decode_chunked($body);
    }

    return [
        'status' => $status,
        'headers' => $responseHeaders,
        'body' => $body,
        'error' => '',
    ];
}

$method = trim((string)($_GET['method'] ?? ''));

// Szybki test dostępności proxy bez połączenia z zewnętrznym API.
if ($method === 'Health') {
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode([
        'ok' => true,
        'service' => 'MKS Debica public proxy',
        'access' => 'public-any-ip',
        'upstream' => 'rj.mks.debica.pl',
        'time' => gmdate('c'),
    ], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

if (!in_array($method, ['PingService', 'GetVehicles'], true)) {
    http_response_code(400);
    header('Content-Type: text/plain; charset=utf-8');
    echo "INVALID_METHOD\n";
    exit;
}

$age = incoming_age();
$ageSource = 'CLIENT';

// Proste użycie z telefonu, przeglądarki lub innego IP nie wymaga nagłówka Age.
// Aplikacja nadal może wysyłać Age, co eliminuje dodatkowy request po stronie PHP.
if ($age === '') {
    $ageSource = 'AUTO';
    if ($method === 'PingService') {
        $age = '403'; // Suma kodów ASCII DEBIC (343) + 60.
    } else {
        $ping = http_socket_get(
            'rj.mks.debica.pl',
            '/AndroidService/SchedulesService.svc/PingService',
            ['Age' => '403'],
            10
        );
        $pingBody = trim(strip_tags($ping['body']));
        if ($ping['status'] !== 200 || !preg_match('/^\d{1,8}$/', $pingBody)) {
            http_response_code(502);
            header('Content-Type: text/plain; charset=utf-8');
            echo "MKS_PING_UNAVAILABLE\n";
            exit;
        }
        $age = (string)(343 + (int)$pingBody);
    }
}

$allowedQuery = [
    'cNbLst',
    'cIdLst',
    'cRouteLst',
    'cTrackLst',
    'cDirLst',
    'cKrsLst',
];

$query = [];
foreach ($allowedQuery as $key) {
    if (array_key_exists($key, $_GET)) {
        $query[$key] = (string)$_GET[$key];
    }
}

$path = '/AndroidService/SchedulesService.svc/' . rawurlencode($method);
if ($query) {
    $path .= '?' . http_build_query($query, '', '&', PHP_QUERY_RFC3986);
}

$result = http_socket_get(
    'rj.mks.debica.pl',
    $path,
    ['Age' => $age]
);

header('X-MKS-Age-Received: ' . ($ageSource === 'CLIENT' ? 'YES' : 'AUTO'));
header('X-MKS-Age-Source: ' . $ageSource);
header('X-MKS-Upstream-Status: ' . $result['status']);

if ($result['status'] === 0) {
    http_response_code(502);
    header('Content-Type: text/plain; charset=utf-8');
    header('X-MKS-Socket-Error: ' . rawurlencode($result['error']));
    echo "MKS_SOCKET_ERROR\n";
    echo $result['error'] . "\n";
    exit;
}

http_response_code($result['status']);

$contentType = $result['headers']['content-type']
    ?? 'application/xml; charset=utf-8';

header('Content-Type: ' . $contentType);
echo $result['body'];
