"""Trusted converters run only inside the artifact service's disposable offline sandbox."""
import ast
import csv
import html
import io
import json
import operator
from pathlib import Path
import re
import subprocess
import uuid
import zipfile


def calculate(cells):
    """Deliberately bounded formula language; never eval, external links, macros or Python calls."""
    cache, active = {}, set()
    def cell(row, col):
        key = (row, col)
        if key in cache: return cache[key]
        if key in active: raise ValueError('#CYCLE!')
        if row < 0 or col < 0 or row >= len(cells) or col >= len(cells[0]): raise ValueError('#REF!')
        active.add(key)
        try:
            value = cells[row][col]
            if isinstance(value, str) and value.startswith('='):
                expr = value[1:].upper().replace('^', '**')
                def ref(match):
                    letters, number = match.group(1), match.group(2)
                    return f'C({int(number)-1},{ord(letters)-65})'
                # Range arguments are expanded before individual references.
                def span(match):
                    a, r, b, s = match.groups()
                    start, end = int(r)-1, int(s)-1
                    if start < 0 or start > end or a > b or end >= len(cells) or ord(b)-65 >= len(cells[0]): raise ValueError('#REF!')
                    return ','.join(f'C({row},{col})' for row in range(start, end+1) for col in range(ord(a)-65, ord(b)-64))
                expr = re.sub(r'\b([A-Z])(\d+):([A-Z])(\d+)\b', span, expr)
                expr = re.sub(r'\b([A-Z])(\d+)\b', ref, expr)
                if len(expr) > 100000: raise ValueError('#LIMIT!')
                tree = ast.parse(expr, mode='eval')
                if sum(1 for _ in ast.walk(tree)) > 1000: raise ValueError('#LIMIT!')
                def number(v):
                    if v is None or v == '': return 0
                    if type(v) not in (int, float, bool): raise ValueError('#VALUE!')
                    if abs(v) > 1e100 or v != v: raise ValueError('#NUM!')
                    return v
                def walk(n):
                    if isinstance(n, ast.Constant) and type(n.value) in (int, float): return number(n.value)
                    if isinstance(n, ast.UnaryOp) and type(n.op) in (ast.USub, ast.UAdd): return (-1 if isinstance(n.op, ast.USub) else 1)*number(walk(n.operand))
                    if isinstance(n, ast.BinOp) and type(n.op) in (ast.Add, ast.Sub, ast.Mult, ast.Div, ast.Pow):
                        a, b = number(walk(n.left)), number(walk(n.right))
                        if isinstance(n.op, ast.Pow) and abs(b) > 10: raise ValueError('#LIMIT!')
                        return number({ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul, ast.Div: operator.truediv, ast.Pow: operator.pow}[type(n.op)](a, b))
                    if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and not n.keywords:
                        name = n.func.id
                        if name == 'C' and len(n.args) == 2 and all(isinstance(a, ast.Constant) and type(a.value) is int for a in n.args): return cell(n.args[0].value, n.args[1].value)
                        raw_values = [walk(a) for a in n.args]
                        values = [v for v in raw_values if type(v) in (int, float)]
                        if name == 'SUM': return sum(values)
                        if name == 'COUNT': return len(values)
                        if values and name == 'AVERAGE': return sum(values)/len(values)
                        if name == 'MIN': return min(values, default=0)
                        if name == 'MAX': return max(values, default=0)
                    raise ValueError('#UNSUPPORTED!')
                value = walk(tree.body)
                if type(value) in (int, float) and (abs(value) > 1e100 or value != value): raise ValueError('#NUM!')
            cache[key] = value
            return value
        finally: active.remove(key)
    output = []
    for row in range(len(cells)):
        line = []
        for col in range(len(cells[row])):
            try: line.append(cell(row, col))
            except ZeroDivisionError: line.append('#DIV/0!')
            except (ValueError, SyntaxError, TypeError, RecursionError, OverflowError) as error:
                line.append(str(error) if str(error).startswith('#') else '#ERROR!')
        output.append(line)
    return output


def markdown_document(value):
    from markdown_it import MarkdownIt
    tokens = MarkdownIt('commonmark', {'html': False}).enable(['table', 'strikethrough']).parse(value)
    blocks, lists, quote = [], [], 0
    kind, level, table, row, in_cell = 'paragraph', 1, None, None, False
    def inline(token):
        value, marks, active = '', [], []
        for child in token.children or []:
            name = {'strong_open':'bold', 'em_open':'italic', 's_open':'strike', 'link_open':'link'}.get(child.type)
            if name:
                mark = {'type':name, 'from':len(value)}
                if name == 'link': mark['href'] = child.attrGet('href')
                active.append(mark)
            elif child.type in {'strong_close','em_close','s_close','link_close'}:
                if active:
                    mark = active.pop(); mark['to'] = len(value)
                    if mark['to'] > mark['from']: marks.append(mark)
            elif child.type == 'code_inline':
                start = len(value); value += child.content
                if child.content: marks.append({'type':'code','from':start,'to':len(value)})
            elif child.type in {'softbreak','hardbreak'}: value += '\n'
            elif child.type == 'image': value += '![' + child.content + '](' + child.attrGet('src') + ')'
            else: value += child.content
        return value, marks
    for token in tokens:
        t = token.type
        if t in {'bullet_list_open','ordered_list_open'}: lists.append('bullet' if t.startswith('bullet') else 'ordered')
        elif t in {'bullet_list_close','ordered_list_close'}: lists.pop()
        elif t == 'blockquote_open': quote += 1
        elif t == 'blockquote_close': quote -= 1
        elif t == 'table_open': table = []
        elif t == 'tr_open': row = []
        elif t in {'th_open','td_open'}: in_cell = True
        elif t in {'th_close','td_close'}: in_cell = False
        elif t == 'tr_close': table.append(row)
        elif t == 'table_close':
            blocks.append({'id':uuid.uuid4().hex,'type':'table','text':'','rows':table}); table = None
        elif t == 'heading_open': kind, level = 'heading', int(token.tag[1])
        elif t == 'paragraph_open': kind, level = ('quote' if quote else lists[-1] if lists else 'paragraph'), 1
        elif t == 'inline':
            text, marks = inline(token)
            if in_cell: row.append(text)
            else: blocks.append({'id':uuid.uuid4().hex,'type':kind,'level':level,'text':text,'marks':marks})
        elif t in {'fence','code_block'}: blocks.append({'id':uuid.uuid4().hex,'type':'code','text':token.content.removesuffix('\n')})
        elif t == 'hr': blocks.append({'id':uuid.uuid4().hex,'type':'paragraph','text':'—'})
    return {'version':1,'blocks':blocks or [{'id':uuid.uuid4().hex,'type':'paragraph','text':''}]}


def markdown_inline(block):
    value = block.get('text', '')
    marks = block.get('marks', [])
    cuts = sorted({0, len(value), *(m[k] for m in marks for k in ('from','to'))})
    output = []
    for start, end in zip(cuts, cuts[1:]):
        segment = value[start:end]
        active = [m for m in marks if m['from'] <= start and m['to'] >= end]
        if any(m['type']=='code' for m in active):
            fence = '`' * (max([len(x) for x in re.findall(r'`+', segment)] or [0]) + 1)
            segment = fence + ' ' + segment + ' ' + fence
        else: segment = re.sub(r'([\\`*_{}\[\]<>#+!|])', r'\\\1', segment)
        for mark in active:
            if mark['type'] in {'bold','italic','strike'}:
                token = {'bold':'**','italic':'_','strike':'~~'}[mark['type']]; segment = token + segment + token
            elif mark['type'] == 'link': segment = '[' + segment + '](' + mark['href'].replace(')', '%29') + ')'
        output.append(segment)
    return ''.join(output)


def markdown(content):
    output = []
    for b in content['blocks']:
        kind = b['type']
        value = b.get('text', '') if kind in {'code','image','table'} else markdown_inline(b)
        if kind == 'heading': value = '#' * b.get('level', 1) + ' ' + value
        elif kind in ('bullet', 'ordered'): value = ('- ' if kind == 'bullet' else '1. ') + value
        elif kind == 'quote': value = '> ' + value.replace('\n', '\n> ')
        elif kind == 'code':
            fence = '`' * max(3, max([len(x) for x in re.findall(r'`+', value)] or [0]) + 1)
            value = fence + '\n' + value + '\n' + fence
        elif kind == 'image': value = '![' + value.replace(']', '\\]') + '](assistant://artifact/' + b['file'] + ')'
        elif kind == 'table':
            rows = ['| ' + ' | '.join(c.replace('|', '\\|').replace('\n', '<br>') for c in row) + ' |' for row in b['rows']]
            rows.insert(1, '| ' + ' | '.join('---' for _ in b['rows'][0]) + ' |')
            value = '\n'.join(rows)
        output.append(value)
    return '\n\n'.join(output) + '\n'


def docx(content, target, inputs):
    from docx import Document
    from docx.shared import Inches
    from docx.oxml import OxmlElement
    from docx.oxml.ns import qn
    doc = Document()
    for b in content['blocks']:
        kind, value = b['type'], b.get('text', '')
        if kind == 'image':
            doc.add_picture(str(inputs / b['file']), width=Inches(5.5))
            if value: doc.add_paragraph(value, 'Caption')
            continue
        if kind == 'table':
            table = doc.add_table(rows=len(b['rows']), cols=len(b['rows'][0])); table.style = 'Table Grid'
            for i, row in enumerate(b['rows']):
                for j, cell in enumerate(row): table.cell(i, j).text = cell
            continue
        style = {'heading': f'Heading {b.get("level",1)}', 'bullet': 'List Bullet', 'ordered': 'List Number', 'quote': 'Quote'}.get(kind)
        p = doc.add_paragraph(style=style)
        boundaries = sorted({0, len(value), *(m[k] for m in b.get('marks', []) for k in ('from', 'to'))})
        for start, end in zip(boundaries, boundaries[1:]):
            marks = [m for m in b.get('marks', []) if m['from'] <= start and m['to'] >= end]
            run = p.add_run(value[start:end]); run.bold = any(m['type'] == 'bold' for m in marks); run.italic = any(m['type'] == 'italic' for m in marks)
            run.font.strike = any(m['type'] == 'strike' for m in marks)
            if kind == 'code' or any(m['type'] == 'code' for m in marks): run.font.name = 'DejaVu Sans Mono'
            link = next((m.get('href') for m in marks if m['type'] == 'link'), None)
            if link:
                from docx.opc.constants import RELATIONSHIP_TYPE as RT
                anchor = OxmlElement('w:hyperlink'); anchor.set(qn('r:id'), p.part.relate_to(link, RT.HYPERLINK, is_external=True))
                anchor.append(run._r); p._p.append(anchor)
    doc.save(target)


def convert_office(path, out, fmt='pdf'):
    profile = Path('/tmp/office-profile/user'); profile.mkdir(parents=True, exist_ok=True)
    (profile / 'registrymodifications.xcu').write_text('''<?xml version="1.0"?><oor:items xmlns:oor="http://openoffice.org/2001/registry"><item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop></item></oor:items>''')
    result = subprocess.run(['/office/program/soffice', '-env:UserInstallation=file:///tmp/office-profile', '--headless', '--norestore', '--convert-to', fmt, '--outdir', str(out), str(path)],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45)
    target = out / (path.stem + '.' + fmt.split(':')[0])
    if result.returncode or not target.is_file(): raise ValueError('Office conversion failed: ' + result.stdout.decode(errors='replace')[-1200:])
    return target


def import_file(path, suffix):
    warnings = []
    if suffix == '.docx':
        from docx import Document
        from docx.text.paragraph import Paragraph
        from docx.text.hyperlink import Hyperlink
        from docx.table import Table
        doc = Document(path); blocks = []
        # Preserve body order, including interleaved tables.
        for element in doc.element.body:
            if element.tag.endswith('}p'):
                p = Paragraph(element, doc)
                kind = 'heading' if p.style.name.startswith('Heading ') else {'List Bullet': 'bullet', 'List Number': 'ordered', 'Quote': 'quote'}.get(p.style.name, 'paragraph')
                b = {'id': uuid.uuid4().hex, 'type': kind, 'text': p.text, 'marks': []}
                if kind == 'heading': b['level'] = int(p.style.name.split()[-1])
                offset = 0
                for child in p.iter_inner_content():
                    linked = isinstance(child, Hyperlink)
                    start = offset
                    for run in child.runs if linked else [child]:
                        for enabled, mark in [(run.bold, 'bold'), (run.italic, 'italic'), (run.font.strike, 'strike')]:
                            if enabled and run.text: b['marks'].append({'type': mark, 'from': offset, 'to': offset+len(run.text)})
                        offset += len(run.text)
                    if linked and offset > start and re.match(r'^(https?://|mailto:|assistant://artifact/)', child.url):
                        b['marks'].append({'type': 'link', 'from': start, 'to': offset, 'href': child.url})
                blocks.append(b)
            elif element.tag.endswith('}tbl'):
                blocks.append({'id': uuid.uuid4().hex, 'type': 'table', 'text': '', 'rows': [[c.text for c in r.cells] for r in Table(element, doc).rows]})
        warnings.append('Imported paragraphs, basic emphasis, safe links, headings, standard lists and tables. Images, custom numbering, headers, footers, comments, tracked changes and advanced layout may be omitted. The original is retained.')
        return 'document', {'version': 1, 'blocks': blocks or [{'id': uuid.uuid4().hex, 'type': 'paragraph', 'text': ''}]}, warnings
    if suffix == '.pptx':
        from pptx import Presentation
        deck = Presentation(path)
        slides = []
        for slide in deck.slides:
            texts = [shape.text for shape in slide.shapes if shape.has_text_frame and shape.text.strip()]
            slides.append({'title': texts[0] if texts else '', 'body': '\n'.join(texts[1:])})
        warnings.append('Imported slide text in shape order. Layout, images, animation and charts are omitted; the original presentation is retained.')
        return 'slides', {'version': 1, 'theme': 'light', 'slides': slides}, warnings
    if suffix in {'.xlsx', '.csv', '.tsv'}:
        if suffix == '.xlsx':
            from openpyxl import load_workbook
            book = load_workbook(path, read_only=True, data_only=False, keep_links=False)
            sheet = book.active
            if sheet.max_row > 100 or sheet.max_column > 26 or sheet.max_row*sheet.max_column > 2000: raise ValueError('Workbook exceeds editor limits (100 rows, 26 columns, 2000 cells)')
            cells = [list(row) for row in sheet.iter_rows(values_only=True)]
            for row in cells:
                for i, v in enumerate(row):
                    if type(v) not in (str, int, float, bool) and v is not None: row[i] = str(v)
            warnings.append('Imported the active sheet and formulas. Other sheets, formatting, charts, names and date types are not editable here. Original retained; unsupported formulas are labeled.')
        else:
            cells = list(csv.reader(io.StringIO(path.read_text('utf-8-sig')), delimiter='\t' if suffix == '.tsv' else ','))
            width = max(map(len, cells), default=1)
            cells = [r + ['']*(width-len(r)) for r in cells]
            warnings.append('CSV values are text. Change a cell type explicitly before calculating.')
        return 'sheet', {'version': 1, 'cells': cells or [['']]}, warnings
    value = path.read_text('utf-8-sig')
    content = markdown_document(value) if suffix == '.md' else {'version':1,'blocks':[{'id':uuid.uuid4().hex,'type':'paragraph','text':value}]}
    if suffix == '.md': warnings.append('Imported supported Markdown text, emphasis, links, lists, headings, code and tables. Nested list layout is flattened; image references remain text. Original retained.')
    return 'document', content, warnings



def main():
    import resource
    resource.setrlimit(resource.RLIMIT_CPU, (50, 50))
    resource.setrlimit(resource.RLIMIT_AS, (2*1024**3, 2*1024**3))
    resource.setrlimit(resource.RLIMIT_FSIZE, (25*1024**2, 25*1024**2))
    resource.setrlimit(resource.RLIMIT_NOFILE, (128, 128))
    resource.setrlimit(resource.RLIMIT_NPROC, (64, 64))
    job = json.loads(Path('/work/job.json').read_text())
    out, inputs = Path('/work/out'), Path('/work/inputs')
    out.mkdir()
    result = {'warnings': []}
    if job['action'] == 'import':
        kind, content, warnings = import_file(inputs / job['file'], job['suffix'])
        result.update(kind=kind, content=content, warnings=warnings)
    elif job['action'] == 'preview':
        import pypdfium2 as pdfium
        file = inputs / job['file']
        if job['suffix'] in {'.docx', '.pptx'}: file = convert_office(file, out)
        if job['suffix'] in {'.png', '.jpg', '.jpeg', '.webp'}:
            from PIL import Image
            img = Image.open(file); img.thumbnail((1400, 1800)); img.convert('RGB').save(out / 'page.png'); result['pages'] = 1
        else:
            pdf = pdfium.PdfDocument(file); page = job.get('page', 0)
            if not 0 <= page < len(pdf): raise ValueError('Page does not exist')
            p = pdf[page]; scale = min(2, 1600/max(p.get_size()))
            p.render(scale=scale).to_pil().save(out / 'page.png'); result['pages'] = len(pdf)
        result['file'] = 'page.png'
    else:
        item, fmt = job['item'], job['format']; content, kind = item['content'], item['kind']
        target = out / ('export.' + fmt)
        if kind == 'document':
            if fmt == 'md': target.write_text(markdown(content))
            elif fmt in {'docx', 'pdf'}:
                word = out / 'source.docx'; docx(content, word, inputs)
                if fmt == 'docx': word.rename(target)
                else: convert_office(word, out).rename(target)
            else: raise ValueError('Choose Markdown, DOCX or PDF')
        elif kind == 'app':
            if fmt != 'html' or content['format'] != 'html':
                if fmt != 'json': raise ValueError('GenUI exports as JSON source')
                target.write_text(json.dumps(content))
            else:
                # Export stays offline; hosted state/bridge calls are deliberately unavailable.
                target.write_text('<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\'; style-src \'unsafe-inline\'; img-src data:; connect-src \'none\'; form-action \'none\'; base-uri \'none\'">' + content['source'])
                result['warnings'].append('Standalone HTML has no FRIDAY state, messages or server bridge.')
        elif kind == 'sheet':
            if fmt == 'csv':
                with target.open('w', newline='') as stream: csv.writer(stream).writerows(content['cells'])
            elif fmt == 'xlsx':
                from openpyxl import Workbook
                book = Workbook(); sheet = book.active
                for row in content['cells']: sheet.append(row)
                book.save(target)
                result['warnings'].append('Formulas are preserved. Excel/LibreOffice recalculates on open; cached formula values are not embedded.')
            else: raise ValueError('Choose XLSX or CSV')
        elif kind == 'slides':
            if fmt == 'pptx':
                from pptx import Presentation
                from pptx.util import Inches, Pt
                from pptx.dml.color import RGBColor
                deck = Presentation(); deck.slide_width = Inches(12); deck.slide_height = Inches(6.75)
                bg, fg = {'light': ('FFFFFF','202024'), 'dark': ('19191E','FFFFFF'), 'warm': ('FFF4E6','432C23')}[content['theme']]
                for value in content['slides']:
                    slide = deck.slides.add_slide(deck.slide_layouts[6])
                    slide.background.fill.solid(); slide.background.fill.fore_color.rgb = RGBColor.from_string(bg)
                    for key, top, height, size in [('title', .5, 1.4, 28), ('body', 2, 4.3, 18)]:
                        box = slide.shapes.add_textbox(Inches(.6), Inches(top), Inches(10.8), Inches(height))
                        box.text_frame.word_wrap = True
                        for i, line in enumerate(value[key].split('\n')):
                            paragraph = box.text_frame.paragraphs[0] if i == 0 else box.text_frame.add_paragraph()
                            paragraph.text = line; paragraph.font.size = Pt(size); paragraph.font.name = 'DejaVu Sans'; paragraph.font.color.rgb = RGBColor.from_string(fg)
                deck.save(target)
                result['file'] = target.name
                (out / 'result.json').write_text(json.dumps(result))
                return
            if fmt != 'pdf': raise ValueError('Slides export as PDF')
            from reportlab.pdfgen import canvas
            from reportlab.lib.colors import HexColor
            from reportlab.pdfbase import pdfmetrics
            from reportlab.pdfbase.ttfonts import TTFont
            font = Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
            pdfmetrics.registerFont(TTFont('Slide', str(font)))
            c = canvas.Canvas(str(target), pagesize=(960,540))
            bg, fg = {'light': ('#ffffff','#202024'), 'dark': ('#19191e','#ffffff'), 'warm': ('#fff4e6','#432c23')}[content['theme']]
            import textwrap
            for slide in content['slides']:
                c.setFillColor(HexColor(bg)); c.rect(0,0,960,540,fill=1,stroke=0); c.setFillColor(HexColor(fg)); c.setFont('Slide',32)
                title_lines = textwrap.wrap(slide['title'], 42) or ['']
                lines = [line for p in slide['body'].split('\n') for line in (textwrap.wrap(p, 75) or [''])]
                if len(title_lines)>2 or len(lines)>12: raise ValueError('Slide text overflows the template. Shorten it or split into another slide.')
                for i, line in enumerate(title_lines): c.drawString(50,470-i*40,line)
                c.setFont('Slide',20)
                for i,line in enumerate(lines): c.drawString(50,350-i*24,line)
                c.showPage()
            c.save()
        result['file'] = target.name
    (out / 'result.json').write_text(json.dumps(result))


if __name__ == '__main__': main()
