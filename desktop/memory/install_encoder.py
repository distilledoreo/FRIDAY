import hashlib,json,urllib.request
from pathlib import Path
import argparse
parser=argparse.ArgumentParser(description='Install the pinned CPU-only memory encoder')
parser.add_argument('memory_root',type=Path)
root=parser.parse_args().memory_root/'model';root.mkdir(parents=True,exist_ok=True)
revision='1110a243fdf4706b3f48f1d95db1a4f5529b4d41'
base='https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2'
with urllib.request.urlopen(base.replace('huggingface.co/','huggingface.co/api/models/')+'/tree/'+revision+'/onnx?expand=true') as r:info=json.load(r)
item=next(i for i in info if i['path']=='onnx/model_quint8_avx2.onnx')
manifest={'repository':'sentence-transformers/all-MiniLM-L6-v2','revision':revision,'files':[]}
for remote,local in [('onnx/model_quint8_avx2.onnx','model.onnx'),('tokenizer.json','tokenizer.json')]:
    path=root/local
    with urllib.request.urlopen(base+'/resolve/'+revision+'/'+remote) as response,path.open('wb') as f:
        while chunk:=response.read(1024*1024):f.write(chunk)
    sha=hashlib.sha256(path.read_bytes()).hexdigest()
    if local=='model.onnx':assert path.stat().st_size==item['size'] and sha==item['lfs']['oid']
    manifest['files'].append({'file':local,'bytes':path.stat().st_size,'sha256':sha})
(root/'manifest.json').write_text(json.dumps(manifest,indent=2))
print('Pinned CPU encoder installed:',sum(f['bytes'] for f in manifest['files']),'bytes; model checksum verified.')
