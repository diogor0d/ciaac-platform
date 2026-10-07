#!/usr/bin/env python3
"""Build the loopback-only peer. Does not run or connect it."""
from pathlib import Path
import argparse, hashlib, os, subprocess
root=Path(__file__).resolve().parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--maven', required=True, type=Path)
parser.add_argument('--self-test', action='store_true', help='run offline bounded packet/identity checks')
args=parser.parse_args()
jdk=Path(os.environ.get('JAVA_HOME','/invalid-jdk'))/'bin'
if not (jdk/'javac').is_file() or not args.maven.is_file():
    raise SystemExit('Supply Java 25 JAVA_HOME and an existing Maven executable.')
build=root.parents[1]/'target/local-arena-client';build.mkdir(parents=True,exist_ok=True)
cpfile=build/'classpath.txt'
subprocess.run([str(args.maven),'-B','-q','-f',str(root/'pom.xml'),
               'dependency:build-classpath','-Dmdep.outputFile='+str(cpfile)],check=True)
classpath=cpfile.read_text().strip()
protocol=[Path(p) for p in classpath.split(os.pathsep) if '/org/geysermc/mcprotocollib/protocol/' in p.replace(os.sep,'/')]
if len(protocol)!=1 or hashlib.sha256(protocol[0].read_bytes()).hexdigest()!='5757ef29742d75b4af90fcdaf3d997e4b6dcf23928e601b4869487286854779a':
    raise SystemExit('The pinned official MCProtocolLib 26.2 artifact digest differs.')
subprocess.run([str(jdk/'javac'),'--release','25','-cp',classpath,'-d',str(build),str(root/'LocalArenaPeer.java')],check=True)
if args.self_test:
    subprocess.run([str(jdk/'javac'),'--release','25','-cp',str(build)+os.pathsep+classpath,
                    '-d',str(build),str(root/'LocalArenaPeerActionsCheck.java')],check=True)
    subprocess.run([str(jdk/'java'),'-cp',str(build)+os.pathsep+classpath,
                    'LocalArenaPeerActionsCheck'],check=True)
print('Compiled local peer; no connection started. Classes/classpath:',build)
