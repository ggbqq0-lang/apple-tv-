"""One local command: start independent fake, run tests, verify payloads, stop fake."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

def main():
    root=Path(__file__).resolve().parents[1]
    os.chdir(root)
    environment=os.environ.copy()
    if not environment.get("JAVA_HOME") and (root/".tools/jdk/bin/java").exists():
        environment["JAVA_HOME"]=str(root/".tools/jdk")
    temporary=tempfile.TemporaryDirectory(prefix="atv-fake-metrics-")
    environment["ATV_FAKE_METRICS"]=str(Path(temporary.name)/"touch.properties")
    environment["ATV_FAKE_DELAY_HID_ACK"]="1"
    fake=subprocess.Popen([sys.executable,"-u","-m","mac_probe.fake_server"],stdout=subprocess.PIPE,text=True,env=environment)
    try:
        info=json.loads(fake.stdout.readline())
        environment["ATV_FAKE_PORT"]=str(info["fake_port"])
        subprocess.run([sys.executable,"-m","pytest","-q"],check=True,env=environment)
        subprocess.run(["./gradlew","test","--console=plain"],check=True,env=environment)
        subprocess.run([sys.executable,"-m","mac_probe.verify_kotlin_payloads"],check=True,env=environment)
    finally:
        fake.terminate()
        try:fake.wait(timeout=5)
        except subprocess.TimeoutExpired:fake.kill();fake.wait()
        temporary.cleanup()

if __name__=="__main__":main()
