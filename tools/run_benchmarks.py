#!/usr/bin/env python3
"""Capture six launch/compilation cases and one first-Search case against one exact profiled release APK."""
from pathlib import Path
import argparse, datetime, hashlib, json, os, shutil, subprocess
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--serial',default='emulator-5554');p.add_argument('--emulator-procedure',action='store_true');a=p.parse_args()
a.out=a.out.resolve();a.out.mkdir(parents=True,exist_ok=False)
sdk=Path(os.environ.get('ANDROID_HOME',Path.home()/'Library/Android/sdk'))
adb=str(sdk/'platform-tools/adb');base=[adb,'-s',a.serial];commands=[]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def run(args):
 r=subprocess.run(args,capture_output=True,text=True)
 commands.append({'argv':args,'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'exit':r.returncode,'stdout':r.stdout,'stderr':r.stderr})
 (a.out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
 if r.returncode:raise RuntimeError(r.stderr or r.stdout)
 return r.stdout
try:
 devices=run([adb,'devices','-l']);connected=[s.split()[0] for s in devices.splitlines() if len(s.split())>1 and s.split()[1]=='device']
 assert connected==[a.serial], 'Exactly one selected device must be connected'
 qemu=run(base+['shell','getprop','ro.kernel.qemu']).strip()=='1'
 if qemu:assert a.emulator_procedure,'Explicit --emulator-procedure required; emulator timings are not representative physical-device results'
 release=ROOT/'app/build/outputs/apk/release/app-release.apk';controller=ROOT/'benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk'
 expected={'release':sha(release),'controller':sha(controller)}
 run(base+['install','-r',str(release)])
 def installed(stage):
  path=run(base+['shell','pm','path','staff.android.startup']).strip().removeprefix('package:')
  dest=a.out/f'installed-{stage}.apk';run(base+['pull',path,str(dest)]);assert sha(dest)==expected['release']
 installed('before')
 def observe(stage):
  result={}
  for name,cmd in [('properties',['getprop']),('battery',['dumpsys','battery']),('thermal',['dumpsys','thermalservice']),('display',['dumpsys','display']),('package',['dumpsys','package','staff.android.startup']),('art',['dumpsys','art']),('font',['settings','get','system','font_scale']),('dark',['cmd','uimode','night'])]:
   (a.out/f'{stage}-{name}.txt').write_text(run(base+['shell']+cmd));result[name]=f'{stage}-{name}.txt'
  return result
 manifest={'status':'STARTING','started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'serial':a.serial,'emulator':qemu,'scope':'Procedure and mechanism on emulator; no representative device or guaranteed profile speedup claim.','artifacts':expected,'fixture':'Six validated Shop products; 4,800 generated search editions fixed per process; local images; 8 MiB process image cache.','setup':'Three iterations each of eager Cold Full, deferred Cold Full/None/Partial Require (warmupIterations=0), deferred Warm Full and Hot Full; three separate fresh-process first-Search iterations with Full AOT record the index section after ready Shop and empty Search. Default JUnit method order, not paired/interleaved. Macrobenchmark owns process/Activity reset for each startup mode. Its cold path attempts kernel-page-cache clearing; no guarantee of device-wide cold storage.','before':observe('before'),'suppressed_errors':['EMULATOR'] if a.emulator_procedure else []}
 (a.out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
 command=[str(ROOT/'gradlew'),'-p',str(ROOT),'--no-daemon','--no-configuration-cache',':benchmark:connectedBenchmarkAndroidTest']
 if a.emulator_procedure:command+=['-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR']
 manifest['invocation']=command
 with (a.out/'invocation.log').open('w') as log: result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=os.environ.copy())
 manifest['exit']=result.returncode;manifest['ended_at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
 for name,source in [('android-results',ROOT/'benchmark/build/outputs/androidTest-results'),('additional-output',ROOT/'benchmark/build/outputs/connected_android_test_additional_output')]:
  if source.exists():shutil.copytree(source,a.out/name)
 (a.out/'logcat.txt').write_text(run(base+['logcat','-d','-s','Benchmark','TestRunner','CG23','ProfileInstaller']))
 manifest['after']=observe('after')
 packages=run(base+['shell','pm','list','packages','staff.android.startup']).splitlines()
 if 'package:staff.android.startup' in packages:
  installed('after');manifest['post_run_readback']='MATCHED'
 else:
  manifest['post_run_readback']='UNAVAILABLE_TARGET_REMOVED_BY_TEST_CLEANUP'
 assert sha(release)==expected['release'] and sha(controller)==expected['controller'], 'Build artifacts changed during measurement'
 manifest['status']='INVOCATION_COMPLETED_REVIEW_REQUIRED' if result.returncode==0 else 'INVOCATION_FAILED'
 manifest['files']={str(f.relative_to(a.out)):sha(f) for f in a.out.rglob('*') if f.is_file() and f.name!='manifest.json'}
 (a.out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
 assert result.returncode==0, 'Inspect retained log and actual tests'
 print('Raw launch evidence retained; independent endpoint/trace review required')
except Exception as error:
 (a.out/'failure.json').write_text(json.dumps({'status':'FAILED_OR_INCOMPLETE','error':str(error)},indent=2)+'\n');raise
