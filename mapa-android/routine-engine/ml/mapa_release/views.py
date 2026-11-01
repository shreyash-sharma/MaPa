"""Hugging Face subset views derived strictly from an immutable final folder."""
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq
from mapa_syn.io import write_json,sha256


def stage_views(dataset,final):
    counts={};payload={}
    for split in ['train','val','test']:
        source=final/f'{split}.parquet';counts[split]={}
        writers={}
        try:
            for subset in ['observed','derived','episodes']:
                path=dataset/'data'/subset/f'{split}.parquet';path.parent.mkdir(parents=True,exist_ok=True)
                writers[subset]=pq.ParquetWriter(path,pq.read_schema(source),compression='zstd')
                counts[split][subset]=0
            for batch in pq.ParquetFile(source).iter_batches():
                table=pa.Table.from_batches([batch])
                for subset,writer in writers.items():
                    selected=table.filter(pc.equal(table['subset'],subset))
                    if len(selected):writer.write_table(selected);counts[split][subset]+=len(selected)
        finally:
            for writer in writers.values():writer.close()
        assert sum(counts[split].values())==pq.ParquetFile(source).metadata.num_rows
    for path in (dataset/'data').rglob('*.parquet'):
        payload[str(path.relative_to(dataset))]=sha256(path)
    readme=dataset/'README.md';text=readme.read_text(encoding='utf-8')
    header,body=text.split('---',2)[1:]
    configs='configs:\n- config_name: all\n  default: true\n  data_files:\n'
    for split in ['train','val','test']:configs+=f'  - split: {split}\n    path: {final.name}/{split}.parquet\n'
    for subset in ['observed','derived','episodes']:
        configs+=f'- config_name: {subset}\n  data_files:\n'
        for split in ['train','val','test']:configs+=f'  - split: {split}\n    path: data/{subset}/{split}.parquet\n'
    # Cards are regenerated before this helper, so configurations are added once.
    readme.write_text('---'+header+configs+'---'+body,encoding='utf-8')
    result=dict(source_final=final.name,rows=counts,sha256=payload,
                definition='Views are exact subset filters; the default all config uses only the selected frozen version, preventing old-version duplication.')
    write_json(dataset/'subset-views.json',result)
    return result
