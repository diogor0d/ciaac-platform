#!/usr/bin/env python3
"""Compila apenas o probe local; não instala nem inicia servidores."""
from pathlib import Path
import os, subprocess, xml.etree.ElementTree as ET
root=Path(__file__).resolve().parent
project=root.parents[1]
jdk=Path(os.environ.get('JAVA_HOME','/invalid-jdk'))/'bin'
reports=project/'target/surefire-reports'
classpath=None
for report in sorted(reports.glob('TEST-*.xml')):
    for prop in ET.parse(report).getroot().findall('./properties/property'):
        if prop.get('name')=='java.class.path':
            classpath=prop.get('value');break
    if classpath:break
if not classpath:raise SystemExit('Executa primeiro mvn verify no repositório canónico.')
if not (jdk/'javac').is_file():raise SystemExit('Define JAVA_HOME para o JDK 25.')
build=project/'target/local-paper-probes';classes=build/'classes';classes.mkdir(parents=True,exist_ok=True)
sources=sorted(root.glob('src/local/harness/*.java'))
subprocess.run([str(jdk/'javac'),'--release','25','-cp',classpath,'-d',str(classes),*[str(source) for source in sources]],check=True)
(classes/'plugin.yml').write_bytes((root/'plugin.yml').read_bytes())
jar=build/'CiaacPistonProbe.jar'
subprocess.run([str(jdk/'jar'),'--create','--file',str(jar),'-C',str(classes),'.'],check=True)
print(jar)
