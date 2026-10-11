#!/usr/bin/env python3
"""Run the explicit profile producer and retain its actual outputs, logs and APK identity."""
from pathlib import Path
import argparse,datetime,hashlib,json,os,shutil,subprocess
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--serial',default='emulator-5554');a=p.parse_args();a.out=a.out.resolve();a.out.mkdir(parents=True,exist_ok=False)
adb=str(Path(os.environ.get('ANDROID_HOME',Path.home()/'Library/Android/sdk'))/'platform-tools/adb');base=[adb,'-s',a.serial]
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
manifest={'status':'STARTING','started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'serial':a.serial,'commands':[]}
def command(args):
 r=subprocess.run(args,capture_output=True,text=True);manifest['commands'].append({'argv':args,'exit':r.returncode,'stdout':r.stdout,'stderr':r.stderr});save()
 if r.returncode:raise RuntimeError(r.stderr or r.stdout)
 return r.stdout
def save():(a.out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
try:
 devices=command([adb,'devices','-l']);assert [s.split()[0] for s in devices.splitlines() if len(s.split())>1 and s.split()[1]=='device']==[a.serial]
 manifest['device_time']=command(base+['shell','date','-u']);manifest['properties']=command(base+['shell','getprop'])
 manifest['artifacts_before']={str(f.relative_to(ROOT)):sha(f) for f in [ROOT/'app/build/outputs/apk/nonMinifiedRelease/app-nonMinifiedRelease.apk',ROOT/'baselineprofile/build/outputs/apk/nonMinifiedRelease/baselineprofile-nonMinifiedRelease.apk']}
 argv=[str(ROOT/'gradlew'),'-p',str(ROOT),'--no-daemon','--no-configuration-cache',':app:generateReleaseBaselineProfile'];manifest['invocation']=argv;save()
 with (a.out/'invocation.log').open('w') as log:r=subprocess.run(argv,stdout=log,stderr=subprocess.STDOUT)
 manifest['exit']=r.returncode
 for name,source in [('android-results',ROOT/'baselineprofile/build/outputs/androidTest-results'),('additional-output',ROOT/'baselineprofile/build/outputs/connected_android_test_additional_output'),('generated-rules',ROOT/'app/src/release/generated/baselineProfiles')]:
  if source.exists():shutil.copytree(source,a.out/name)
 (a.out/'logcat.txt').write_text(command(base+['logcat','-d','-s','Benchmark','TestRunner','CG23']))
 manifest['ended_at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
 for rel,h in manifest['artifacts_before'].items():assert sha(ROOT/rel)==h,'Generation APK changed: '+rel
 packages=command(base+['shell','pm','list','packages','staff.android.startup']).splitlines()
 if 'package:staff.android.startup' in packages:
  installed=command(base+['shell','pm','path','staff.android.startup']).strip().removeprefix('package:')
  command(base+['pull',installed,str(a.out/'installed-generation.apk')])
  assert sha(a.out/'installed-generation.apk')==manifest['artifacts_before']['app/build/outputs/apk/nonMinifiedRelease/app-nonMinifiedRelease.apk']
  manifest['post_run_readback']='MATCHED'
 else:
  manifest['post_run_readback']='UNAVAILABLE_TARGET_REMOVED_BY_TEST_CLEANUP'
 manifest['status']='GENERATION_COMPLETED_REVIEW_REQUIRED' if r.returncode==0 else 'GENERATION_FAILED'
 manifest['files']={str(f.relative_to(a.out)):sha(f) for f in a.out.rglob('*') if f.is_file() and f.name!='manifest.json'};save()
 assert r.returncode==0,'Inspect retained generation failure'
 print('Generation outputs retained; packaging and independent verification still required')
except Exception as e:
 manifest['status']='FAILED_OR_INCOMPLETE';manifest['error']=str(e);save();raise
