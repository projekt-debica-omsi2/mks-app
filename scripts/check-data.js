const fs = require('fs');
const vm = require('vm');
const src = fs.readFileSync('app/src/main/assets/data.js', 'utf8');
const sandbox = { window: {} };
vm.runInNewContext(src, sandbox, { timeout: 3000 });
const stops = sandbox.window.MKS_STOPS;
const boards = sandbox.window.MKS_SCHEDULES;
if (!Array.isArray(stops) || stops.length < 350) throw Error('Brak przystanków');
if (!Array.isArray(boards) || boards.length < 800) throw Error('Brak rozkładów');
const stopNums = new Set(stops.map(s => s.number));
for (const item of boards) {
  if (!stopNums.has(item.stop_number)) throw Error('Brak przystanku ' + item.stop_number);
  if (!item.line || !item.routeId || !item.sections) throw Error('Niepełna tabliczka');
}
console.log(`OK: ${stops.length} przystanków, ${boards.length} tabliczek, ${new Set(boards.map(b => b.line)).size} linii.`);
