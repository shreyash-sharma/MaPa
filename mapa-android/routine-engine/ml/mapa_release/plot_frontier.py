"""Export validation-only false-alert/catch tradeoffs, including failed trials."""
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from .common import ROOT
from mapa_syn.io import read_json


def plot():
    runs=[]
    for folder in sorted((ROOT/'models').iterdir()):
        if not (folder/'validation-trials.json').exists():continue
        config=read_json(folder/'complete.json')
        if config.get('alert_policy')!='latched_until_sign_of_life':continue
        rows=read_json(folder/'validation-trials.json')
        if not all('positives_by_family' in r['methods']['MaPa-Rhythm-Base'] for r in rows):continue
        runs.append((folder.name,rows))
    if not runs:return None
    fig,axes=plt.subplots(1,3,figsize=(13,4),sharey=True)
    for ax,severity in zip(axes,['mild','clear','severe']):
        for name,rows in runs:
            points=[]
            for row in rows:
                m=row['methods']['MaPa-Rhythm-Base'];c,n=m['positives_by_family']['behaviour'][severity]
                if n:points.append((m['false_alerts_per_person_week'],c/n))
            if points:
                points.sort();ax.plot(*zip(*points),alpha=.65,lw=1,label=name.replace('cnn-',''))
        for baseline,marker in [('4h_rule','s'),('statistics_only','D')]:
            m=runs[0][1][0]['methods'][baseline];c,n=m['positives_by_family']['behaviour'][severity]
            if n:ax.scatter([m['false_alerts_per_person_week']],[c/n],marker=marker,color='black',s=45,label=baseline,zorder=5)
        ax.set_title(severity.capitalize());ax.set_xlabel('False alerts / observed person-week')
        ax.set_xlim(left=0);ax.set_ylim(0,1.03);ax.grid(alpha=.2)
    axes[0].set_ylabel('Behavior-scenario detection rate')
    handles,labels=axes[-1].get_legend_handles_labels()
    fig.legend(handles,labels,loc='lower center',bbox_to_anchor=(.5,-.1),ncol=4,fontsize=7)
    fig.suptitle('Validation tradeoffs — shared latch policy; no test selection; failures retained',fontsize=11)
    fig.tight_layout();folder=ROOT/'eval';folder.mkdir(exist_ok=True)
    path=folder/'validation-frontier.png';fig.savefig(path,dpi=160,bbox_inches='tight');plt.close(fig)
    return path


if __name__=='__main__':print(plot())
