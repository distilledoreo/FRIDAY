"""Small pinned ONNX sentence encoder. CPU only: never competes for VRAM."""
from pathlib import Path
import threading
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

class Encoder:
    def __init__(self,root):
        root=Path(root)
        self.tokenizer=Tokenizer.from_file(str(root/'tokenizer.json'))
        self.tokenizer.enable_truncation(max_length=256);self.tokenizer.enable_padding()
        options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
        self.session=ort.InferenceSession(str(root/'model.onnx'),options,providers=['CPUExecutionProvider'])
        self.lock=threading.Lock()
    def encode(self,texts):
        with self.lock:
            batch=self.tokenizer.encode_batch(texts)
            inputs={'input_ids':np.array([e.ids for e in batch],dtype=np.int64),'attention_mask':np.array([e.attention_mask for e in batch],dtype=np.int64),'token_type_ids':np.array([e.type_ids for e in batch],dtype=np.int64)}
            inputs={i.name:inputs[i.name] for i in self.session.get_inputs()}
            hidden=self.session.run(None,inputs)[0];mask=inputs['attention_mask'][...,None]
            result=(hidden*mask).sum(axis=1)/np.maximum(mask.sum(axis=1),1)
            result/=np.maximum(np.linalg.norm(result,axis=1,keepdims=True),1e-9)
            return result.astype(np.float32)
