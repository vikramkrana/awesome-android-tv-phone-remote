"""The README animation (docs/how-it-works.gif) from the screenshots in docs/screens. Needs Pillow.
Usage: python3 tools/how_it_works.py docs/how-it-works.gif"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import sys
import os
ROOT=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
S=os.path.join(ROOT, 'docs', 'screens') + '/'
FONT=os.path.join(ROOT, 'app', 'src', 'main', 'res', 'font', 'outfit.ttf')
W,H=1200,640
def font(sz,w=600):
    f=ImageFont.truetype(FONT,sz)
    try: f.set_variation_by_axes([w])
    except Exception: pass
    return f
BG=(18,18,22)
def rounded(im,r):
    m=Image.new('L',im.size,0); ImageDraw.Draw(m).rounded_rectangle((0,0,im.width-1,im.height-1),r,fill=255)
    out=Image.new('RGBA',im.size); out.paste(im,(0,0),m); return out
def phone(name, h=520):
    im=Image.open(S+name).convert('RGB')
    w=int(im.width*h/im.height); im=im.resize((w,h),Image.LANCZOS)
    # bezel
    b=12; fr=Image.new('RGB',(w+2*b,h+2*b),(44,44,50))
    fr=rounded(fr,46); fr.paste(rounded(im,36),(b,b),rounded(im,36))
    return fr
def tv(name, w=780):
    im=Image.open(S+name).convert('RGB')
    h=int(im.height*w/im.width); im=im.resize((w,h),Image.LANCZOS)
    b=10; fr=Image.new('RGB',(w+2*b,h+2*b),(30,30,34)); fr=rounded(fr,14)
    fr.paste(rounded(im,6),(b,b),rounded(im,6)); return fr
def scene(step, title, tvname, phname):
    c=Image.new('RGB',(W,H),BG); d=ImageDraw.Draw(c)
    d.text((48,40),step,font=font(22,500),fill=(150,150,160))
    d.text((48,68),title,font=font(38,650),fill=(244,242,248))
    t=tv(tvname); p=phone(phname)
    ty=140+(H-140-t.height)//2
    c.paste(t,(40,ty),t)
    c.paste(p,(W-p.width-50,(H-p.height)//2+40),p)
    return c
scenes=[
 ("STEP 1","Press Home: a QR code appears","tv-home.png","phone-pair.png"),
 ("STEP 2","Scan it, type the PIN from the big screen","tv-pin.png","phone-pin.png"),
 ("STEP 3","Paired once, never asked again","tv-app.png","phone-done.png"),
 ("YOUR PHONE IS THE REMOTE","D-pad, volume, Back, Home","tv-home.png","phone-remote.png"),
 ("APPS","Every app on the device, one tap","tv-home.png","phone-apps.png"),
 ("TYPING AND VOICE","Search YouTube from the phone","tv-home.png","phone-search.png"),
 ("NOW PLAYING","Seek, pause, sleep timer, photos","tv-home.png","phone-projector.png"),
]
frames=[];durs=[]
imgs=[scene(*s) for s in scenes]
for i,im in enumerate(imgs):
    frames.append(im); durs.append(2300)
    nxt=imgs[(i+1)%len(imgs)]
    for k in range(1,5):
        frames.append(Image.blend(im,nxt,k/5)); durs.append(70)
pal=[f.convert('P',palette=Image.ADAPTIVE,colors=200) for f in frames]
pal[0].save(sys.argv[1],save_all=True,append_images=pal[1:],duration=durs,loop=0,optimize=True)

