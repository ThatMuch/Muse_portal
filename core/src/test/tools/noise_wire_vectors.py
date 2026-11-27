# Produit des vecteurs d'octets avec le code Python du SDK (modules purs, sans dépendance).
import importlib.util, json, struct, sys, types
noise_dir, out = sys.argv[1], sys.argv[2]
pkg = types.ModuleType("sdknoise"); pkg.__path__ = [noise_dir]; sys.modules["sdknoise"] = pkg
def load(name):
    spec = importlib.util.spec_from_file_location("sdknoise." + name, f"{noise_dir}/{name}.py")
    module = importlib.util.module_from_spec(spec); sys.modules[spec.name] = module; spec.loader.exec_module(module); return module
load("_proto"); f = load("framing"); e = load("envelope")
def req(service, frame): return e.encode_service_request(e.ServiceRequest(service=service, payload=e.encode_service_frame(frame))).hex()
def res(frame): return e.encode_service_response(e.ServiceResponse(payload=e.encode_service_frame(frame))).hex()
big = bytes(i % 251 for i in range(70000))
doc = {
 "source": "linux/src/musegadget/noise/{_proto,framing,envelope}.py, commit a5f809f (Apache-2.0)",
 "open_control": req(e.ServiceType.SERVICE_GADGET, e.ServiceFrame.request(1, e.ApplicationRequest(verb="POST", path="/api/gadget/control", end_body=False))),
 "body_chunk": req(e.ServiceType.SERVICE_GADGET, e.ServiceFrame.body_chunk(1, e.BodyChunk(data=struct.pack("<I", 2) + b"{}", end_body=False))),
 "daemon_request": req(e.ServiceType.SERVICE_DAEMON, e.ServiceFrame.request(300, e.ApplicationRequest(verb="GET", path="/x", headers=[e.Header("a", "b")], body=b"hi", end_body=True))),
 "reset": req(e.ServiceType.SERVICE_GADGET, e.ServiceFrame.reset(1, e.Reset(code=e.ResetCode.CANCELLED, reason="stop"))),
 "response_ok": res(e.ServiceFrame.response(1, e.ApplicationResponse(status=200, headers=[e.Header("content-type", "x")], body=b"abc", end_body=False))),
 "response_chunk_end": res(e.ServiceFrame.body_chunk(1, e.BodyChunk(data=b"zz", end_body=True))),
 "response_reset": res(e.ServiceFrame.reset(1, e.Reset(code=e.ResetCode.REFUSED_STREAM, reason="non"))),
 "frame_small": f.encode_noise_frames(b"hello", chunk_id=-5)[0].hex(),
 "frame_empty": f.encode_noise_frames(b"", chunk_id=7)[0].hex(),
 "frame_big": [x.hex() for x in f.encode_noise_frames(big, chunk_id=1234567890123)],
}
json.dump(doc, open(out, "w"), indent=1); print("vecteurs :", len(doc) - 1)
