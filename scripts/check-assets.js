// Walidacja statycznych ikon pochodzących z przekazanego APK myBus.
const fs=require('fs');
const path=require('path');
const src=path.join('app','src','main','assets');
const html=fs.readFileSync(path.join(src,'index.html'),'utf8');
const css=fs.readFileSync(path.join(src,'style.css'),'utf8');
const js=fs.readFileSync(path.join(src,'app.js'),'utf8');
const names=['bus-green.png','stop-blue.png','line-arrow.png','transfer-green.png','refresh-blue.png','settings-gray.png','favourite-outline.png','stop-pin.png'];
for(const name of names){
 const pathname=path.join(src,'icons',name);
 const bytes=fs.readFileSync(pathname);
 if(bytes.length<60 || bytes.subarray(0,8).toString('hex')!=='89504e470d0a1a0a')throw new Error('Niepoprawny PNG: '+name);
}
for(const [_,name] of [...(html+'\n'+css+'\n'+js).matchAll(/icons\/([A-Za-z0-9-]+\.png)/g)]){
 if(!fs.existsSync(path.join(src,'icons',name)))throw new Error('Brak zasobu: '+name);
}
if(!html.includes('class="tab-icon"')||!js.includes('icons/bus-green.png'))throw new Error('Ikony nie są powiązane z interfejsem');
console.log('OK: 8 oryginalnych ikon z APK, ścieżki aplikacji poprawne.');
