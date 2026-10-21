/* MKS Dębica — interfejs i statyczne dane, bez zewnętrznych bibliotek JavaScript. */
(() => {
'use strict';
const stops = window.MKS_STOPS || [];
const boards = window.MKS_SCHEDULES || [];
const DAYS = ['Roboczy','Roboczy wakacyjny/feryjny','Sobota','Niedziele i święta'];
const $ = id => document.getElementById(id);
const escapeHtml = s => String(s ?? '').replace(/[&<>"']/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const norm = value => String(value||'').toLocaleLowerCase('pl').normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/ł/g,'l').replace(/[^a-z0-9]+/g,' ').trim();
const getStore = (key, fallback) => {try{const v=localStorage.getItem(key);return v===null?fallback:JSON.parse(v)}catch(_){return fallback}};
const setStore = (key,value) => {try{localStorage.setItem(key,JSON.stringify(value))}catch(_){}};
let favs = new Set(getStore('mks_favourites_v1', []));
let currentScreen = 'live', activeStop = null, gpsTimer = null;
let currentVehicles = [], lastFetched=0, lastPosition=null, lineFilter='all', stopMode='all', currentLineDetail=null;
// GPS korzysta najpierw BEZPOŚREDNIO z API MKS. Proxy to tylko opcjonalne źródło zapasowe.
// Zachowaj wcześniejszy własny adres użytkownika, ale nie wymuszaj starego portu 8088.
const previousProxy = getStore('mks_proxy_v1','');
let proxy = getStore('mks_proxy_fallback_v2',
  previousProxy === 'http://e-dębica.pl:8088' ? '' : previousProxy);

const byNumber=new Map(stops.map(s=>[String(s.number),s]));
const byStop=new Map(), byLine=new Map(), routes=new Map();
for(const b of boards){
  const sn=String(b.stop_number), line=String(b.line);
  if(!byStop.has(sn))byStop.set(sn,[]);byStop.get(sn).push(b);
  if(!byLine.has(line))byLine.set(line,[]);byLine.get(line).push(b);
  const k=b.routeId+'|'+b.dir;
  if(!routes.has(k))routes.set(k,{id:k,line,dir:b.dir,stops:new Set(),directions:new Set()});
  routes.get(k).stops.add(sn);routes.get(k).directions.add(b.direction);
}
const lines=[...byLine.keys()].sort((a,b)=>Number(a)-Number(b));
function toast(message){const e=$('toast');e.textContent=message;e.classList.remove('hidden');clearTimeout(toast.timer);toast.timer=setTimeout(()=>e.classList.add('hidden'),3200)}
function formatDistance(m){return m<1000?Math.round(m)+' m':(m/1000).toFixed(1)+' km'}
function distance(a,b){const r=Math.PI/180, x=(a.lng-b.lng)*r*Math.cos((a.lat+b.lat)/2*r),y=(a.lat-b.lat)*r;return Math.sqrt(x*x+y*y)*6371000}
function dayNow(){const n=new Date().getDay();return n===6?DAYS[2]:n===0?DAYS[3]:DAYS[0]}
let chosenDay=dayNow();
function startNav(id){
  if(!['live','stops','lines','route','favs','settings'].includes(id))return;
  currentScreen=id;
  for(const el of document.querySelectorAll('.screen'))el.classList.toggle('active',el.id==='screen-'+id);
  for(const el of document.querySelectorAll('#nav button'))el.classList.toggle('chosen',el.dataset.screen===id);
  window.scrollTo(0,0);
  if(id==='live'){renderMap();scheduleGps();}else{clearInterval(gpsTimer);gpsTimer=null;}
  if(id==='stops')renderStops();if(id==='favs')renderFavs();if(id==='lines')renderLines();
}
for(const nav of document.querySelectorAll('#nav button'))nav.addEventListener('click',()=>{closeBoard();currentLineDetail=null;startNav(nav.dataset.screen)});
function stopRow(s,extra=''){
  const sn=String(s.number), count=new Set((byStop.get(sn)||[]).map(b=>b.line)).size;
  return `<button class="item" data-stop="${escapeHtml(sn)}"><div class="left"><div class="tag stop-tag">${escapeHtml(sn)}</div><div><strong>${escapeHtml(s.name)}</strong><div class="sub">${count} ${count===1?'linia':'linii'}${extra?' • '+escapeHtml(extra):''}</div></div></div><span class="chevron">›</span></button>`;
}
function bindStopButtons(container){container.querySelectorAll('[data-stop]').forEach(e=>e.addEventListener('click',()=>openBoard(e.dataset.stop)))}
function renderStops(){
  const q=norm($('stopSearch').value);
  let all=stops.filter(s=>norm(s.name+' '+s.number).includes(q));
  if(stopMode==='near'){
    if(lastPosition)all.sort((a,b)=>distance(a,lastPosition)-distance(b,lastPosition));
    else stopMode='all';
  }else all.sort((a,b)=>a.name.localeCompare(b.name,'pl',{numeric:true}));
  $('stopMatches').textContent=all.length+' przyst.';
  const subset=all;
  $('stopsList').innerHTML=subset.length?subset.map(s=>stopRow(s,stopMode==='near'&&lastPosition?formatDistance(distance(s,lastPosition)):'' )).join(''):'<div class="empty">Nie znaleziono przystanku.</div>';
  bindStopButtons($('stopsList'));
}
$('stopSearch').addEventListener('input',()=>renderStops());
$('nearestBtn').addEventListener('click',()=>{stopMode='near';startNav('stops');getLocation()});
$('allStopsBtn').addEventListener('click',()=>{stopMode='all';renderStops()});
$('locateTop').addEventListener('click',()=>{stopMode='near';startNav('stops');getLocation()});
function getLocation(){
  if(window.MksAndroid){window.MksAndroid.getLocation();}
  else if(navigator.geolocation)navigator.geolocation.getCurrentPosition(p=>window.onMksLocation({ok:true,lat:p.coords.latitude,lng:p.coords.longitude}),()=>toast('Nie uzyskano lokalizacji'));
  else toast('Lokalizacja dostępna na urządzeniu z GPS');
}
window.onMksLocation=result=>{
  if(!result.ok){toast(result.error||'Lokalizacja niedostępna');return}
  lastPosition={lat:result.lat,lng:result.lng};
  if(stopMode==='near')renderStops();renderMap();
  toast('Znaleziono lokalizację — odległości są orientacyjne');
};
function renderLines(){
  const el=$('linesList');
  if(currentLineDetail){
    const line=currentLineDetail, all=byLine.get(line)||[], groups=new Map();
    for(const b of all){const key=b.dir+' / '+b.direction;if(!groups.has(key))groups.set(key,[]);groups.get(key).push(b)}
    el.className='list';el.innerHTML=`<button class="small-btn" id="backToLines">← Wszystkie linie</button><div class="card"><h2>Linia ${escapeHtml(line)}</h2><div class="muted">Wykaz przystanków w poszczególnych kierunkach. Kolejność alfabetyczna — to nie jest kolejność przejazdu.</div></div>`+
      [...groups.entries()].map(([direction,entries])=>`<div class="card"><h3>Kierunek ${escapeHtml(direction.split(' / ').slice(1).join(' / '))}</h3><div class="list">${[...new Map(entries.map(b=>[b.stop_number,b])).values()].sort((a,b)=>a.stop_name.localeCompare(b.stop_name,'pl')).map(b=>stopRow(byNumber.get(String(b.stop_number))||{number:b.stop_number,name:b.stop_name})).join('')}</div></div>`).join('');
    $('backToLines').addEventListener('click',()=>{currentLineDetail=null;renderLines()});bindStopButtons(el);return;
  }
  const q=norm($('lineSearch').value);el.className='grid-lines';
  el.innerHTML=lines.filter(l=>norm(l+' '+byLine.get(l).map(e=>e.direction).join(' ')).includes(q)).map(l=>{
    const b=byLine.get(l),dest=[...new Set(b.map(x=>x.direction))].slice(0,2);
    return `<button class="line-card" data-line="${escapeHtml(l)}"><div class="line-number">${escapeHtml(l)}</div><div class="dest">${escapeHtml(dest.join(' / '))}</div><div class="sub">${new Set(b.map(e=>e.stop_number)).size} przystanków</div></button>`;
  }).join('')||'<div class="empty">Nie znaleziono linii.</div>';
  el.querySelectorAll('[data-line]').forEach(e=>e.addEventListener('click',()=>{currentLineDetail=e.dataset.line;renderLines();window.scrollTo(0,0)}));
}
$('lineSearch').addEventListener('input',()=>{currentLineDetail=null;renderLines()});
function currentClock(){const n=new Date();return String(n.getHours()).padStart(2,'0')+':'+String(n.getMinutes()).padStart(2,'0')}
function openBoard(number){
  const s=byNumber.get(String(number));if(!s)return;
  activeStop=String(number);
  $('boardTitle').textContent=s.name;
  $('boardSubtitle').textContent='Przystanek nr '+s.number+' • '+(byStop.get(activeStop)||[]).length+' tabliczek';
  $('dayType').value=chosenDay;
  $('stopOverlay').classList.remove('hidden');
  renderBoard();
}
function closeBoard(){$('stopOverlay').classList.add('hidden');activeStop=null}
$('closeBoard').addEventListener('click',closeBoard);
$('stopOverlay').addEventListener('click',e=>{if(e.target.id==='stopOverlay')closeBoard()});
$('dayType').addEventListener('change',()=>{chosenDay=$('dayType').value;renderBoard()});
function renderBoard(){
  if(!activeStop)return;
  const arr=(byStop.get(activeStop)||[]).sort((a,b)=>Number(a.line)-Number(b.line));
  const today=dayNow()===chosenDay,now=currentClock();
  $('boardFavorite').textContent=favs.has(activeStop)?'★ Ulubiony':'☆ Ulubiony';
  $('boardDepartures').innerHTML=arr.map(b=>{
    const times=b.sections?.[chosenDay]||[];
    return `<div class="board-group"><div class="board-group-header"><b>${escapeHtml(b.line)}</b><span class="heading">${escapeHtml(b.direction)}</span><a href="${escapeHtml(b.url)}" class="external-link">WWW ↗</a></div><div class="departures">${times.length?times.map(t=>`<span class="departure ${today&&t.time>=now?'next':''}">${escapeHtml(t.time)}${t.mark?'<small>'+escapeHtml(t.mark)+'</small>':''}</span>`).join(''):'<span class="muted">Brak kursów tego dnia</span>'}</div></div>`;
  }).join('')||'<div class="empty">Brak danych rozkładowych dla tego przystanku.</div>';
  $('boardDepartures').querySelectorAll('.external-link').forEach(a=>a.setAttribute('target','_blank'));
}
$('boardFavorite').addEventListener('click',()=>{
  if(!activeStop)return;
  if(favs.has(activeStop))favs.delete(activeStop);else favs.add(activeStop);
  setStore('mks_favourites_v1',[...favs]);renderBoard();renderFavs();
});
$('boardShare').addEventListener('click',()=>{
  const s=byNumber.get(activeStop);if(!s)return;
  const out=['MKS Dębica — '+s.name+' (#'+s.number+')',chosenDay];
  for(const b of (byStop.get(activeStop)||[]))out.push('Linia '+b.line+' → '+b.direction+': '+(b.sections?.[chosenDay]||[]).map(x=>x.time+(x.mark||'')).join(', '));
  out.push('Godziny z zapisanej kopii rozkładów — przed podróżą sprawdź aktualność.');
  if(window.MksAndroid)window.MksAndroid.shareText(out.join('\n'));
  else if(navigator.clipboard)navigator.clipboard.writeText(out.join('\n')).then(()=>toast('Skopiowano tabliczkę'));
  else toast('Udostępnianie dostępne w aplikacji');
});
function renderFavs(){const sl=stops.filter(s=>favs.has(String(s.number))).sort((a,b)=>a.name.localeCompare(b.name,'pl'));
 $('favsList').innerHTML=sl.length?sl.map(s=>stopRow(s)).join(''):'<div class="empty">Brak ulubionych. Otwórz przystanek i dotknij ☆ Ulubiony.</div>';bindStopButtons($('favsList'));}
function getStopFromInput(v){const n=norm(v),nNumber=v.match(/^#?(\d+)$/);return stops.find(s=>norm(s.name+' (#'+s.number+')')===n)||stops.find(s=>norm(s.name)===n)|| (nNumber?byNumber.get(nNumber[1]):null) || null}
function planJourney(){
 const from=getStopFromInput($('routeFrom').value),to=getStopFromInput($('routeTo').value),target=$('routeResults');
 if(!from||!to){target.innerHTML='<div class="empty">Wybierz przystanki z listy podpowiedzi, wpisując pełną nazwę lub numer.</div>';return}
 if(from.number===to.number){target.innerHTML='<div class="empty">Początek i cel to ten sam przystanek.</div>';return}
 const a=String(from.number),b=String(to.number),rs=[...routes.values()];
 const starts=rs.filter(r=>r.stops.has(a)),ends=rs.filter(r=>r.stops.has(b));
 const direct=starts.filter(r=>r.stops.has(b));
 const results=[];
 const uniqueD=new Set();
 for(const d of direct){const key=d.line+'|'+d.dir;if(uniqueD.has(key))continue;uniqueD.add(key);
  results.push({kind:'direct',a:d,score:0});}
 const candidate=[];
 for(const first of starts)for(const second of ends){if(first.id===second.id)continue;
   if(first.line===second.line)continue;
   for(const mid of first.stops){if(!second.stops.has(mid)||mid===a||mid===b)continue;
      const stop=byNumber.get(mid);if(!stop)continue;
      const ratio=(distance(from,stop)+distance(stop,to))/Math.max(distance(from,to),1);
      candidate.push({kind:'transfer',a:first,b:second,mid,score:ratio});
   }
 }
 candidate.sort((x,y)=>x.score-y.score);
 const seen=new Set();
 for(const c of candidate){const key=c.a.line+'|'+c.b.line+'|'+c.mid;if(seen.has(key))continue;seen.add(key);results.push(c);if(results.length>=10)break}
 target.innerHTML=results.length?`<div class="section-head"><h2>Propozycje połączeń</h2><span class="muted">${results.length} propozycji</span></div>`+
 results.map(r=>{
  if(r.kind==='direct')return `<div class="trip-card"><div class="trip-head"><span class="tag">${escapeHtml(r.a.line)}</span><b>Bez przesiadki — możliwa linia wspólna</b></div><div class="trip-leg">${escapeHtml(from.name)} → ${escapeHtml(to.name)}<br>Sprawdź kierunek oraz godzinę na tabliczce.</div><button class="small-btn" data-stop="${escapeHtml(a)}">Rozkład startu</button></div>`;
  const mid=byNumber.get(r.mid);
  return `<div class="trip-card"><div class="trip-head"><span class="tag">${escapeHtml(r.a.line)}</span>→<span class="tag">${escapeHtml(r.b.line)}</span><b class="transfer">1 możliwa przesiadka</b></div><div class="trip-leg">Start: ${escapeHtml(from.name)}<br>Przesiadka: <b>${escapeHtml(mid.name)}</b> (#${escapeHtml(r.mid)})<br>Cel: ${escapeHtml(to.name)}</div><button class="small-btn" data-stop="${escapeHtml(r.mid)}">Sprawdź rozkład przesiadki</button></div>`;
 }).join(''):'<div class="empty">Nie znaleziono wspólnej linii ani przystanku przesiadkowego w zapisanej bazie.</div>';
 bindStopButtons(target);
}
for(const s of stops){const o=document.createElement('option');o.value=s.name+' (#'+s.number+')';$('allStopNames').appendChild(o)}
$('planBtn').addEventListener('click',planJourney);
$('swapStops').addEventListener('click',()=>{const a=$('routeFrom').value;$('routeFrom').value=$('routeTo').value;$('routeTo').value=a;planJourney()});
// GPS: pozycje pochodzą wyłącznie z odpowiedzi oryginalnego API, bez symulacji.
function scheduleGps(){if(gpsTimer)return;refreshGps();gpsTimer=setInterval(()=>{if(currentScreen==='live')refreshGps()},25000)}
function refreshGps(){
 $('liveStatus').classList.remove('bad');$('liveStatus').textContent='◌ Łączenie bezpośrednio z MKS…';
 if(window.MksAndroid)window.MksAndroid.refreshGps(proxy);
 else window.onMksGps({ok:false,error:'GPS działa w wersji Android po zbudowaniu APK.'});
}
window.onMksGps=data=>{
 if(!data.ok){
  $('liveStatus').classList.remove('ok');$('liveStatus').classList.add('bad');
  $('liveStatus').textContent='⚠ GPS niedostępny: '+(data.error||'błąd serwera');
  if(lastFetched)$('lastGps').textContent='Ostatnie dane nieaktualne • '+new Date(lastFetched).toLocaleTimeString('pl-PL');
  return;
 }
 lastFetched=data.fetchedAt||Date.now();
 const old=new Map(currentVehicles.map(v=>[v.id,v]));
 currentVehicles=(data.vehicles||[]).map(v=>({ ...v,
  line:v.line||old.get(v.id)?.line||'?',direction:v.direction||old.get(v.id)?.direction||'brak danych' }));
 $('liveStatus').classList.remove('bad');$('liveStatus').classList.add('ok');
 $('liveStatus').textContent='● GPS '+(data.source||'MKS')+' • aktualizacja '+new Date(lastFetched).toLocaleTimeString('pl-PL');
 $('lastGps').textContent=new Date(lastFetched).toLocaleTimeString('pl-PL');
 renderGpsFilters();renderVehicles();renderMap();
};
function renderGpsFilters(){const keys=[...new Set(currentVehicles.map(v=>v.line))].sort((a,b)=>Number(a)-Number(b));
 if(lineFilter!=='all'&&!keys.includes(lineFilter))lineFilter='all';
 $('liveFilters').innerHTML=['all',...keys].map(l=>`<button class="pill ${lineFilter===l?'selected':''}" data-filter="${escapeHtml(l)}">${l==='all'?'Wszystkie':escapeHtml(l)}</button>`).join('');
 $('liveFilters').querySelectorAll('button').forEach(b=>b.addEventListener('click',()=>{lineFilter=b.dataset.filter;renderGpsFilters();renderVehicles();renderMap()}));
}
function visibleVehicles(){return currentVehicles.filter(v=>lineFilter==='all'||v.line===lineFilter)}
function renderVehicles(){
 const vv=visibleVehicles();$('vehicleCount').textContent=vv.length;
 $('vehicleList').innerHTML=vv.length?vv.map(v=>`<button class="item" data-vehicle="${escapeHtml(v.id)}"><div class="left"><div class="tag">${escapeHtml(v.line||'?')}</div><div><strong>Autobus ${escapeHtml(v.number||v.id)}</strong><div class="sub">${escapeHtml(v.direction||'brak kierunku')} • ${formatDelaySeconds(v.delaySeconds)}</div></div></div><span class="chevron">⌖</span></button>`).join(''):'<div class="empty">Brak pojazdów dla wybranej linii lub brak danych GPS.</div>';
 $('vehicleList').querySelectorAll('[data-vehicle]').forEach(e=>e.addEventListener('click',()=>{const v=currentVehicles.find(x=>x.id===e.dataset.vehicle);if(v){mapCenter={lat:v.lat,lng:v.lng};mapZoom=15;renderMap();window.scrollTo({top:$('mksMap').getBoundingClientRect().top+window.scrollY-92,behavior:'smooth'});toast('Linia '+v.line+' • autobus '+(v.number||v.id))}}));
}
function formatDelaySeconds(seconds){const n=Math.round(Number(seconds)||0);return n===0?'wg GPS: bez opóźnienia':(n>0?'opóźnienie ':'przyspieszenie ')+Math.round(Math.abs(n)/60)+' min'}
$('gpsRefresh').addEventListener('click',refreshGps);
// Mapa kafelkowa OpenStreetMap: lokalny silnik projekcji Web Mercator.
const map=$('mksMap');let mapCenter={lat:50.05135,lng:21.41127},mapZoom=12;
const tileSize=256;const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
const tx=(lng,z)=>(lng+180)/360*tileSize*2**z;
const ty=(lat,z)=>(1-Math.log(Math.tan(clamp(lat,-85.05,85.05)*Math.PI/180)+1/Math.cos(clamp(lat,-85.05,85.05)*Math.PI/180))/Math.PI)/2*tileSize*2**z;
const ll=(x,y,z)=>{const size=tileSize*2**z,lng=x/size*360-180, lat=Math.atan(Math.sinh(Math.PI*(1-2*y/size)))*180/Math.PI;return{lat,lng}};
function renderMap(){
 if(currentScreen!=='live')return;
 const w=map.clientWidth,h=map.clientHeight,z=mapZoom;
 if(!w||!h)return;
 const x=tx(mapCenter.lng,z)-w/2, y=ty(mapCenter.lat,z)-h/2, max=2**z;
 let tiles='';
 for(let xx=Math.floor(x/256);xx<=Math.floor((x+w)/256);xx++)for(let yy=Math.floor(y/256);yy<=Math.floor((y+h)/256);yy++){
   if(yy<0||yy>=max)continue;
   const tileX=(xx%max+max)%max, left=Math.floor(xx*256-x),top=Math.floor(yy*256-y);
   tiles+=`<img class="tile" loading="lazy" draggable="false" style="left:${left}px;top:${top}px" src="https://tile.openstreetmap.org/${z}/${tileX}/${yy}.png" alt="">`;
 }
 $('mapTiles').innerHTML=tiles;
 let markers='';
 if(z>=14){for(const s of stops){if(s.lng==null||s.lat==null)continue;
  const l=tx(s.lng,z)-x,t=ty(s.lat,z)-y;
  if(l>=0&&l<=w&&t>=0&&t<=h)markers+=`<button class="pin stop-pin" data-stop="${escapeHtml(s.number)}" title="${escapeHtml(s.name)}" style="left:${l}px;top:${t}px"></button>`;
 }}
 for(const v of visibleVehicles()){
   if(!Number.isFinite(v.lat)||!Number.isFinite(v.lng))continue;
   const l=tx(v.lng,z)-x,t=ty(v.lat,z)-y;
   if(l>=-25&&l<=w+25&&t>=0&&t<=h+25)markers+=`<button class="pin" data-vehicle="${escapeHtml(v.id)}" title="Linia ${escapeHtml(v.line)} · ${escapeHtml(v.direction)}" style="left:${l}px;top:${t}px">${escapeHtml(v.line)}</button>`;
 }
 if(lastPosition){const l=tx(lastPosition.lng,z)-x,t=ty(lastPosition.lat,z)-y;
  if(l>=0&&l<=w&&t>=0&&t<=h)markers+=`<div class="pin user-pin" style="left:${l}px;top:${t}px" title="Twoja pozycja"></div>`;
 }
 $('mapMarkers').innerHTML=markers;
 $('mapMarkers').querySelectorAll('[data-stop]').forEach(e=>e.addEventListener('click',()=>openBoard(e.dataset.stop)));
 $('mapMarkers').querySelectorAll('[data-vehicle]').forEach(e=>e.addEventListener('click',()=>{
   const v=currentVehicles.find(x=>x.id===e.dataset.vehicle);if(v)toast('Autobus '+(v.number||v.id)+' • linia '+v.line+' → '+v.direction);
 }));
}
function zoomBy(value){mapZoom=clamp(mapZoom+value,9,18);renderMap()}
$('zoomIn').addEventListener('click',()=>zoomBy(1));$('zoomOut').addEventListener('click',()=>zoomBy(-1));
$('mapHome').addEventListener('click',()=>{mapCenter={lat:50.05135,lng:21.41127};mapZoom=12;renderMap()});
let drag=null;
map.addEventListener('pointerdown',e=>{if(e.target.closest('button,a'))return;drag={px:e.clientX,py:e.clientY,lon:mapCenter.lng,lat:mapCenter.lat};map.setPointerCapture(e.pointerId)});
map.addEventListener('pointermove',e=>{if(!drag)return;
 const nx=tx(drag.lon,mapZoom)-(e.clientX-drag.px),ny=ty(drag.lat,mapZoom)-(e.clientY-drag.py);
 mapCenter=ll(nx,ny,mapZoom);mapCenter.lat=clamp(mapCenter.lat,-85,85);renderMap();
});
map.addEventListener('pointerup',()=>drag=null);map.addEventListener('pointercancel',()=>drag=null);
map.addEventListener('wheel',e=>{e.preventDefault();zoomBy(e.deltaY<0?1:-1)},{passive:false});
window.addEventListener('resize',()=>renderMap());
// Ustawienia
$('proxyInput').value=proxy;
$('saveProxy').addEventListener('click',()=>{
 const raw=$('proxyInput').value.trim();
 if(raw&&(!/^https?:\/\//i.test(raw)||/\s/.test(raw))){toast('Adres musi zaczynać się od http:// lub https://');return}
 proxy=raw.replace(/\/+$/,'');setStore('mks_proxy_fallback_v2',proxy);$('proxyFeedback').textContent=proxy?'Zapisano zapasowe proxy. Sprawdzam GPS bezpośrednio z MKS…':'Zapasowe proxy wyłączone. Sprawdzam GPS bezpośrednio z MKS…';
 startNav('live');refreshGps();
});
$('dataCounts').textContent=stops.length+' przystanków, '+lines.length+' linii, '+boards.length+' tabliczek';
window.mksBack=()=>{if(activeStop){closeBoard();return}if(currentLineDetail){currentLineDetail=null;renderLines();return}if(currentScreen!=='live')startNav('live');else if(window.MksAndroid)window.MksAndroid.closeApp();else toast('Jesteś na ekranie głównym')};
renderGpsFilters();renderStops();renderLines();renderFavs();
setTimeout(scheduleGps,700);
})();
