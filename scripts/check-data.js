const fs = require('fs');
const vm = require('vm');
const path = require('path');
const assets = path.join(__dirname, '..', 'app/src/main/assets');
const stops = JSON.parse(fs.readFileSync(path.join(assets, 'stops.json'), 'utf8'));
const state = JSON.parse(fs.readFileSync(path.join(assets, 'boards.json'), 'utf8'));
const boards = state.conn;
if (!Array.isArray(stops) || stops.length !== 374) throw Error('Nieprawidłowa liczba przystanków: ' + stops.length);
if (!Array.isArray(boards) || boards.length !== 812) throw Error('Nieprawidłowa liczba tabliczek: ' + boards.length);
const ids = new Set(stops.map(s => String(s.number)));
if (ids.size !== stops.length) throw Error('Duplikaty numerów przystanków');
for (const s of stops) if (!(s.lat > 49 && s.lat < 51 && s.lng > 20 && s.lng < 23)) throw Error('Błędne współrzędne przystanku: ' + s.number);
for (const b of boards) {
 if (!ids.has(String(b.stop_number))) throw Error('Brak przystanku: ' + b.stop_number);
 if (!b.line || !b.direction || !b.sections || !b.routeId) throw Error('Brak danych tabliczki');
 for (const rows of Object.values(b.sections)) for (const row of rows) if (!/^\d{2}:\d{2}$/.test(row.time)) throw Error('Błędna godzina odjazdu: ' + row.time);
}
const lines = [...new Set(boards.map(b=>b.line))];
if (lines.length !== 14) throw Error('Nieprawidłowa liczba linii: '+lines.length);
const sandbox = {window:{}};
vm.runInNewContext(fs.readFileSync(path.join(assets,'data.js'),'utf8'),sandbox,{timeout:5000});
if (!Array.isArray(sandbox.window.MKS_STOPS) || sandbox.window.MKS_STOPS.length !== stops.length) throw Error('Nieprawidłowe dane JS przystanków');
if (!sandbox.window.MKS_BOARDS || sandbox.window.MKS_BOARDS.conn.length !== boards.length) throw Error('Nieprawidłowe dane JS rozkładów');
console.log(`OK: ${stops.length} przystanki, ${boards.length} tabliczek, ${lines.length} linii`);
