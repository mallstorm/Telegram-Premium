#!/usr/bin/env python3
"""Bundle pinned Tesseract fast models at build time; never download personal content."""
from pathlib import Path
import hashlib,urllib.request
root=Path(__file__).resolve().parents[1]
target=root/'upstream/Telegram/TMessagesProj/src/main/assets/telepremium/ocr'
target.mkdir(parents=True,exist_ok=True)
models={'eng':'7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2','rus':'e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225'}
for name,expected in models.items():
 path=target/(name+'.traineddata')
 if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest()==expected:continue
 data=urllib.request.urlopen('https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/'+name+'.traineddata',timeout=90).read()
 if hashlib.sha256(data).hexdigest()!=expected:raise SystemExit('OCR model SHA mismatch: '+name)
 temporary=path.with_suffix('.tmp');temporary.write_bytes(data);temporary.replace(path)
 print('Verified bundled OCR model:',name,len(data))

# Include the upstream Apache-2.0 model license in the application assets.
license_data=urllib.request.urlopen("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/LICENSE",timeout=45).read()
(target/"LICENSE").write_bytes(license_data)
