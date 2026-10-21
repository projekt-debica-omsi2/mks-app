#!/usr/bin/env python3
"""Regeneruje offline data.js z plików data/ dostarczonych przez użytkownika."""
import json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
state = json.loads((root / 'data/mks_static_state.json').read_text(encoding='utf-8-sig'))
stops = json.loads((root / 'data/mks_stops.json').read_text(encoding='utf-8-sig'))
assert isinstance(state.get('conn'), list) and isinstance(stops, list)
output = root / 'app/src/main/assets/data.js'
output.write_text('/* Rozkłady i przystanki z dołączonych plików danych. */\nwindow.MKS_SCHEDULES = '
                  + json.dumps(state['conn'], ensure_ascii=False, separators=(',', ':'))
                  + ';\nwindow.MKS_STOPS = '
                  + json.dumps(stops, ensure_ascii=False, separators=(',', ':')) + ';\n', encoding='utf-8')
print(f'OK: {len(stops)} przystanków, {len(state["conn"])} tabliczek → {output}')
