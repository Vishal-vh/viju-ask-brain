<?php
// Teen Patti PHP server (polling). Upload to any PHP hosting. Folder must be writable (state.json banta hai).
header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type');
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') exit;

const MAXP = 5, TURN = 30, STALE = 20;
$BOOTS = [10, 50, 100, 500];

function fresh() {
    return ['phase'=>'wait','pot'=>0,'stake'=>10,'boot'=>10,'turn'=>0,'dealer'=>-1,'winner'=>-2,
            'show'=>false,'msg'=>'Players ka wait…','ts'=>time(),'seats'=>[]];
}
function hv($c) { $r = $c % 13 + 1; return $r == 1 ? 14 : $r; }
function score($h) {
    $v = array_map('hv', $h); rsort($v);
    $s = array_map(fn($c) => intdiv($c, 13), $h);
    $fl = $s[0] == $s[1] && $s[1] == $s[2];
    $seq = $v[0]-1 == $v[1] && $v[1]-1 == $v[2]; $t = $v;
    if (!$seq && $v[0] == 14 && $v[1] == 3 && $v[2] == 2) { $seq = true; $t = [13,13,13]; }
    $cat = ($v[0]==$v[1] && $v[1]==$v[2]) ? 6 : ($seq && $fl ? 5 : ($seq ? 4 : ($fl ? 3 : (($v[0]==$v[1] || $v[1]==$v[2]) ? 2 : 1))));
    if ($cat == 2) $t = [$v[0]==$v[1] ? $v[0] : $v[1], $v[0]==$v[1] ? $v[2] : $v[0], 0];
    if ($cat == 6) $t = [$v[0], 0, 0];
    $a = 0; foreach (array_merge([$cat], $t) as $x) $a = $a * 15 + $x;
    return $a;
}
function nact() { global $st; return count(array_filter($st['seats'], fn($p) => !$p['packed'])); }
function cost($p) { global $st; return $p['seen'] ? $st['stake'] * 2 : $st['stake']; }
function pay(&$p, $a) { global $st; $x = min($a, $p['chips']); $p['chips'] -= $x; $st['pot'] += $x; }
function nxt() {
    global $st; $n = count($st['seats']); $i = 0;
    do { $st['turn'] = ($st['turn'] + 1) % $n; $i++; } while ($st['seats'][$st['turn']]['packed'] && $i < 9);
}
function endRound($w, $sh) {
    global $st;
    $st['phase'] = 'end'; $st['show'] = $sh;
    $wid = null;
    if ($w >= 0) { $st['seats'][$w]['chips'] += $st['pot']; $wid = $st['seats'][$w]['id']; $st['msg'] = '🏆 ' . $st['seats'][$w]['name'] . ' jeeta +' . $st['pot']; }
    else {
        $n = max(1, nact()); $sh2 = intdiv($st['pot'], $n);
        foreach ($st['seats'] as &$p) if (!$p['packed']) $p['chips'] += $sh2;
        unset($p); $st['msg'] = 'Tie — pot split';
    }
    $st['pot'] = 0;
    $st['seats'] = array_values(array_filter($st['seats'], fn($p) => empty($p['gone'])));
    $st['winner'] = -1;
    foreach ($st['seats'] as $i => $p) if ($p['id'] === $wid) $st['winner'] = $i;
    $st['dealer'] = min($st['dealer'], count($st['seats']) - 1);
}
function startRound() {
    global $st;
    if (count($st['seats']) < 2 || $st['phase'] === 'play') return;
    $d = range(0, 51); shuffle($d);
    $st['stake'] = $st['boot']; $st['pot'] = 0; $st['show'] = false; $st['winner'] = -2;
    foreach ($st['seats'] as &$p) {
        if ($p['chips'] < $st['boot'] * 20) $p['chips'] = 1000;
        $p['seen'] = false; $p['packed'] = false; pay($p, $st['boot']);
        $p['hand'] = [array_pop($d), array_pop($d), array_pop($d)];
    }
    unset($p);
    $st['dealer'] = ($st['dealer'] + 1) % count($st['seats']); $st['turn'] = $st['dealer']; nxt();
    $st['phase'] = 'play'; $st['msg'] = 'Round shuru — boot ' . $st['boot']; $st['ts'] = time();
}
function doPack($i) {
    global $st;
    if (!isset($st['seats'][$i]) || $st['seats'][$i]['packed']) return;
    $st['seats'][$i]['packed'] = true; $st['msg'] = $st['seats'][$i]['name'] . ' pack';
    if (nact() === 1) {
        foreach ($st['seats'] as $k => $p) if (!$p['packed']) { endRound($k, false); return; }
    }
    if ($st['turn'] === $i) { nxt(); $st['ts'] = time(); }
}
function action($i, $a) {
    global $st;
    if ($st['phase'] !== 'play' || $st['turn'] !== $i) return;
    $p = &$st['seats'][$i];
    if ($a === 'see') { $p['seen'] = true; return; }
    if ($a === 'pack') { doPack($i); return; }
    if ($a === 'chaal') { pay($p, cost($p)); $st['msg'] = $p['name'] . ($p['seen'] ? ' chaal ' : ' blind ') . $st['stake']; }
    elseif ($a === 'raise') {
        if ($st['stake'] * 2 <= $st['boot'] * 128) $st['stake'] *= 2;
        pay($p, cost($p)); $st['msg'] = $p['name'] . ' RAISE → ' . $st['stake'];
    } elseif ($a === 'show') {
        if (nact() !== 2) return;
        pay($p, cost($p));
        $o = -1; foreach ($st['seats'] as $k => $q) if (!$q['packed'] && $k !== $i) $o = $k;
        $x = score($p['hand']); $y = score($st['seats'][$o]['hand']);
        endRound($x > $y ? $i : ($y > $x ? $o : -1), true); return;
    } else return;
    nxt(); $st['ts'] = time();
}

$fp = fopen(__DIR__ . '/state.json', 'c+');
flock($fp, LOCK_EX);
$st = json_decode(stream_get_contents($fp), true);
if (!is_array($st) || !isset($st['seats'])) $st = fresh();

$m = json_decode(file_get_contents('php://input'), true) ?: [];
$pid = substr(preg_replace('/[^a-z0-9]/i', '', $m['pid'] ?? ''), 0, 40);
if ($pid === '') { flock($fp, LOCK_UN); echo '{"error":"pid"}'; exit; }

// presence + cleanup
foreach ($st['seats'] as $k => $p) if ($p['id'] === $pid) $st['seats'][$k]['ls'] = time();
for ($i = 0; $i < count($st['seats']); $i++) {
    if (time() - $st['seats'][$i]['ls'] > STALE && empty($st['seats'][$i]['gone'])) {
        if ($st['phase'] === 'play') { $st['seats'][$i]['gone'] = true; doPack($i); if ($st['phase'] !== 'play') break; }
        else { array_splice($st['seats'], $i, 1); $i--; }
    }
}
if ($st['phase'] === 'play' && time() - $st['ts'] > TURN) doPack($st['turn']);

$me = -1; foreach ($st['seats'] as $k => $p) if ($p['id'] === $pid) $me = $k;
$t = $m['t'] ?? 'poll';
if ($t === 'sit' && $me < 0 && count($st['seats']) < MAXP && $st['phase'] !== 'play') {
    $st['seats'][] = ['id'=>$pid,'name'=>mb_substr(strip_tags($m['name'] ?? 'Player'), 0, 10),'chips'=>1000,'seen'=>false,'packed'=>false,'hand'=>[],'ls'=>time()];
    $me = count($st['seats']) - 1;
} elseif ($t === 'leave' && $me >= 0 && $st['phase'] !== 'play') { array_splice($st['seats'], $me, 1); $me = -1; }
elseif ($t === 'start' && $me >= 0) startRound();
elseif ($t === 'boot' && $me >= 0 && $st['phase'] !== 'play') $st['boot'] = $BOOTS[(array_search($st['boot'], $BOOTS) + 1) % 4];
elseif ($t === 'a' && $me >= 0) action($me, $m['a'] ?? '');

foreach ($st['seats'] as $k => $p) if ($p['id'] === $pid) $me = $k;
$out = [
    'you'=>$me,'phase'=>$st['phase'],'pot'=>$st['pot'],'stake'=>$st['stake'],'boot'=>$st['boot'],'turn'=>$st['turn'],
    'dealer'=>$st['dealer'],'winner'=>$st['winner'],'show'=>$st['show'],'msg'=>$st['msg'],'left'=>max(0, TURN - (time() - $st['ts'])),
    'seats'=>array_map(function ($p, $i) use ($me, $st) {
        $vis = ($i === $me && $p['seen']) || ($st['show'] && !$p['packed']);
        return ['name'=>$p['name'],'chips'=>$p['chips'],'seen'=>$p['seen'],'packed'=>$p['packed'],
                'hand'=>$vis ? $p['hand'] : array_map(fn($c) => -1, $p['hand'])];
    }, $st['seats'], array_keys($st['seats']))
];
ftruncate($fp, 0); rewind($fp); fwrite($fp, json_encode($st)); fflush($fp);
flock($fp, LOCK_UN);
echo json_encode($out);
  
