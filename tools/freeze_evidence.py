#!/usr/bin/env python3
"""Bind final reusable source, retained built artifacts and completed raw evidence."""
from pathlib import Path
import datetime,hashlib,json,os,subprocess,shutil
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'evidence/final-source';assert not OUT.exists(),'Never overwrite an earlier freeze'
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
sources=[]
for directory,dirs,names in os.walk(ROOT):
 dirs[:]=[d for d in dirs if d not in ['evidence','build','.gradle','.kotlin','__pycache__','.git']]
 for name in names:
  p=Path(directory)/name
  if name in ['local.properties','README.md','verification.md'] or name.endswith('.log'):continue
  rel=p.relative_to(ROOT);dest=OUT/rel;dest.parent.mkdir(parents=True,exist_ok=True)
  if os.uname().sysname=='Darwin':subprocess.run(['cp','-c',str(p),str(dest)],check=True)
  else:shutil.copy2(p,dest)
  assert sha(p)==sha(dest);sources.append({'path':str(rel),'snapshot':str(dest.relative_to(ROOT)),'sha256':sha(p)})
artifacts=[]
for manifest in (ROOT/'evidence').glob('*/manifest.json'):
 m=json.loads(manifest.read_text())
 if m.get('status')!='SOURCE_AND_ARTIFACT_IDENTITY_ONLY':continue
 for rel,h in m['sources'].items():assert sha(manifest.parent/'source'/rel)==h
 for rel,h in m['artifacts'].items():
  assert sha(ROOT/rel)==h;artifacts.append({'path':rel,'sha256':h,'scope':m['kind'],'manifest':str(manifest.relative_to(ROOT))})
evidence=[]
for p in sorted((ROOT/'evidence').rglob('*')):
 if p.is_file() and 'final-source' not in p.parts and p.name!='runtime-freeze.json':evidence.append({'path':str(p.relative_to(ROOT)),'sha256':sha(p)})
(ROOT/'evidence/runtime-freeze.json').write_text(json.dumps({'status':'FROZEN_FOR_INDEPENDENT_REVIEW','date':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sources':sources,'artifacts':artifacts,'evidence_files':evidence,'scope':'Final source and retained attempts. Old interrupted generation is explicitly superseded after the critical-photo SideEffect repair. Only the final optimized measurement APK owns startup timings. Debug native appearance has separate pixel/behavior evidence; emulator timings are not representative physical-device claims.'},indent=2)+'\n')
print(json.dumps({'sources':len(sources),'artifacts':len(artifacts),'evidence_files':len(evidence)}))
