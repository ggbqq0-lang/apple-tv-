"""Loopback-only upstream fake for Kotlin integration tests; never a LAN service."""
import asyncio
import importlib.util
import json
import logging
import os
import time
from pathlib import Path
from pyatv.const import KeyboardFocusState

logging.disable(logging.CRITICAL)

async def main():
    source=Path(__file__).resolve().parents[1]/"tests/reference/pyatv_fake_companion.py"
    spec=importlib.util.spec_from_file_location("upstream_companion_fake",source)
    module=importlib.util.module_from_spec(spec)
    import sys
    sys.modules[spec.name]=module
    spec.loader.exec_module(module)
    state=module.FakeCompanionState()
    state.rti_focus_state=KeyboardFocusState.Focused
    class Service(module.FakeCompanionService):
        def send_response(self,request,content):
            if os.environ.get("ATV_FAKE_DELAY_HID_ACK")=="1" and request.get("_i")=="_hidC" and request.get("_c",{}).get("_hBtS")==1:
                # Delay only the ACK, not processing: key-up must still arrive promptly.
                self.loop.call_later(.3,super().send_response,request,content)
            else:super().send_response(request,content)
        def handle__hidc(self,message):
            content=message["_c"];command=content["_hidC"]
            if not hasattr(self,"down_times"):self.down_times={}
            if content["_hBtS"]==1:self.down_times[command]=time.monotonic()
            super().handle__hidc(message)
            destination=os.environ.get("ATV_FAKE_METRICS")
            if destination:Path(destination+".key-state").write_text(f"command={command}\npressed={str(content['_hBtS']==1).lower()}\n")
            if content["_hBtS"]==2 and command in self.down_times:
                elapsed=(time.monotonic()-self.down_times.pop(command))*1000
                destination=os.environ.get("ATV_FAKE_METRICS")
                if destination:Path(destination+".keys").write_text(f"command={command}\nheld_ms={elapsed:.3f}\n")
        def handle__hidt(self,message):
            super().handle__hidt(message)
            destination=os.environ.get("ATV_FAKE_METRICS")
            if destination:
                content=message["_c"]
                self.touch_count=getattr(self,"touch_count",0)+1
                previous=getattr(self,"previous_touch_ns",-1)
                self.touch_time_ordered=getattr(self,"touch_time_ordered",True) and content["_ns"]>=previous
                self.previous_touch_ns=content["_ns"]
                Path(destination).write_text(f"phase={content['_tPh']}\nx={content['_cx']}\ny={content['_cy']}\ncount={self.touch_count}\nordered={str(self.touch_time_ordered).lower()}\n")
        def connection_lost(self, exc):
            # The upstream fake retains RTI state after a client disconnects.
            # Reset only this client's RTI session to allow reconnect tests.
            if self in state.rti_clients:
                state.rti_clients.remove(self)
                state.rti_session_uuid=None
            super().connection_lost(exc)
    server=await asyncio.get_running_loop().create_server(lambda:Service(state),"127.0.0.1",0)
    port=server.sockets[0].getsockname()[1]
    print(json.dumps({"fake_port":port,"pin":"public upstream fixture 1111","bind":"127.0.0.1"}),flush=True)
    async with server: await server.serve_forever()

if __name__=="__main__":asyncio.run(main())
