"""Append only individually checked issuer announcements; never change dates or prices."""
import hashlib
import json
import os
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

import psycopg
from quant_research.input_review import apply, validate

work = Path('build/p2-v14-20261027')
# Independently transcribed announcement terms: record, payment, USD per share, retained capture.
confirmed = {
 'AOS': ('2025-07-31','2025-08-15','0.34',12,'https://aosmith.gcs-web.com/news-releases/news-release-details/o-smith-reports-quarterly-dividend-3'),
 'APA': ('2025-07-22','2025-08-22','0.25',1,'https://investor.apacorp.com/news-releases/news-release-details/apa-corporation-declares-cash-dividend-common-shares-12/'),
 'AXP': ('2025-07-03','2025-08-08','0.82',1,'https://www.americanexpress.com/en-us/newsroom/articles/financial-news/american-express-declares-regular-quarterly-dividend0.html'),
 'CL': ('2025-07-18','2025-08-15','0.52',1,'https://investor.colgatepalmolive.com/dividend-history'),
 'CVS': ('2025-07-22','2025-08-01','0.665',1,'https://www.cvshealth.com/news/company-news/cvs-health-declares-quarterly-dividend-july-2025.html'),
 'DE': ('2025-06-30','2025-08-08','1.62',9,'https://www.prnewswire.com/news-releases/deere--company-announces-quarterly-dividend-302467335.html'),
 'DRI': ('2025-07-10','2025-08-01','1.5',2,'https://investor.darden.com/news/news-details/2025/Darden-Restaurants-Reports-Fiscal-2025-Fourth-Quarter-and-Full-Year-Results-Increases-Quarterly-Dividend-Authorizes-New-1-Billion-Share-Repurchase-Program-And-Provides-Fiscal-2026-Outlook/'),
 'FCX': ('2025-07-15','2025-08-01','0.15',2,'https://investors.fcx.com/investors/news-releases/news-release-details/2025/Freeport-Declares-Quarterly-Cash-Dividends-on-Common-Stock-7861e79af/default.aspx'),
 'FERG': ('2025-06-20','2025-08-06','0.83',2,'https://www.corporate.ferguson.com/pressroom/news-releases/news-details/2025/Ferguson-Declares-Dividend/default.aspx'),
 'GEV': ('2025-07-21','2025-08-18','0.25',3,'https://www.gevernova.com/news/press-releases/ge-vernova-declares-third-quarter-2025-dividend'),
 'GIS': ('2025-07-10','2025-08-01','0.61',3,'https://investors.generalmills.com/press-releases/press-release-details/2025/General-Mills-Reports-Fiscal-2025-Fourth-quarter-and-Full-year-Results-and-Provides-Fiscal-2026-Outlook/'),
 'GL': ('2025-07-03','2025-08-01','0.27',12,'https://www.prnewswire.com/news-releases/globe-life-inc-declares-dividend-302437556.html'),
 'HRL': ('2025-07-14','2025-08-15','0.29',3,'https://www.hormelfoods.com/newsroom/press-releases/hormel-foods-corporation-declares-quarterly-dividend-50/'),
 'KMI': ('2025-07-31','2025-08-15','0.2925',4,'https://ir.kindermorgan.com/news/news-details/2025/Kinder-Morgan-Reports-Second-Quarter-2025-Financial-Results/default.aspx'),
 'LNT': ('2025-07-31','2025-08-15','0.5075',4,'https://investors.alliantenergy.com/News--Presentations/news/news-details/2025/Alliant-Energy-Corporation-Declares-Quarterly-Common-Stock-Dividend-8396cb737/default.aspx'),
 'LOW': ('2025-07-23','2025-08-06','1.2',4,'https://corporate.lowes.com/newsroom/press-releases/lowes-companies-inc-announces-increase-quarterly-cash-dividend-120-share-05-30-25'),
 'MRSH': ('2025-07-24','2025-08-15','0.9',11,'https://www.nasdaq.com/press-release/marsh-mclennan-increases-quarterly-cash-dividend-2025-07-09'),
 'MS': ('2025-07-31','2025-08-15','1',5,'https://www.morganstanley.com/about-us-ir/shareholder/2q2025.pdf?cid=SM_CORP_LINKEDIN_morgan-stanley_20250716'),
 'NI': ('2025-07-31','2025-08-20','0.28',5,'https://investors.nisource.com/financial-news/news-details/2025/NiSource-Declares-Common-Stock-Dividend-b5775f9dc/default.aspx'),
 'PAYX': ('2025-07-21','2025-08-29','1.08',10,'https://finance.yahoo.com/news/paychex-declares-quarterly-dividend-201500999.html'),
 'PNR': ('2025-07-18','2025-08-01','0.25',6,'https://investors.pentair.com/news-releases/news-release-details/pentair-announces-quarterly-cash-dividend-025-0'),
 'RVTY': ('2025-07-18','2025-08-08','0.07',11,'https://www.stocktitan.net/news/PKI/revvity-board-declares-quarterly-thykxmtpa5ve.html'),
 'STZ': ('2025-07-30','2025-08-14','1.02',6,'https://ir.cbrands.com/stock-data/dividend-history'),
 'TRGP': ('2025-07-31','2025-08-15','1',8,'https://www.targaresources.com/news-releases/news-release-details/targa-resources-corp-announces-quarterly-common-dividend-and-0'),
 'VLO': ('2025-07-31','2025-09-02','1.13',7,'https://investorvalero.com/news/news-details/2025/Valero-Energy-Corporation-Declares-Regular-Cash-Dividend-on-Common-Stock-b7458d891/default.aspx'),
 'WSM': ('2025-07-18','2025-08-22','0.66',7,'https://ir.williams-sonomainc.com/investor-information/news-releases/news-release-details/2025/Williams-Sonoma-Inc--declares-quarterly-cash-dividend/default.aspx'),
 'WST': ('2025-07-30','2025-08-06','0.21',8,'https://investor.westpharma.com/news-releases/news-release-details/west-announces-first-quarter-2025-results-increases-adjusted'),
 'ZTS': ('2025-07-18','2025-09-03','0.5',8,'https://investor.zoetis.com/news/news-details/2025/Zoetis-Declares-Third-Quarter-2025-Dividend/default.aspx'),
}
settings = dict(os.environ)
for line in Path('infrastructure/.env').read_text(encoding='utf-8-sig').splitlines():
 if line.strip() and not line.lstrip().startswith('#') and '=' in line:
  key,value=line.split('=',1); settings[key.strip()]=value.strip().strip('"').strip("'")
audit=json.loads(Path('Documentation/evidence/p2/flexible/remaining-audit.json').read_text())
reports=[]
target=work/'reviews';target.mkdir(exist_ok=True)
with psycopg.connect(host='127.0.0.1',port=6543,dbname='quant_platform',user='quant_scoring',
                     password=settings.get('SCORING_DB_PASSWORD','local-quant_scoring-development-only')) as connection:
 with connection.transaction():
  for row in audit['rows']:
   symbol=row['symbol']
   if symbol not in confirmed: continue
   record,payment,amount,batch,uri=confirmed[symbol]
   if len(row['issues'])!=1:
    reports.append(dict(symbol=symbol,applied=False,reason='MULTIPLE_PROVIDER_ACTIONS_REQUIRE_RECONCILIATION'))
    continue
   issue=row['issues'][0];action=issue['provider_content']
   assert action['type']=='cash_dividends'
   assert action['symbol']==('MMC' if symbol=='MRSH' else symbol)
   assert action['record_date']==record and action['payable_date']==payment
   assert Decimal(str(action['rate']))==Decimal(amount)
   assert not action['special'] and action['foreign']==(symbol=='PNR')
   file=work/'sources'/f'web-batch{batch}.json'
   assert uri in file.read_text(encoding='utf-8')
   with connection.cursor() as c:
    c.execute('''SELECT q.evidence::text,iss.legal_name,iss.cik FROM operations.data_quality_issues q
     JOIN reference.instruments i USING(instrument_id) JOIN reference.issuers iss USING(issuer_id)
     WHERE q.data_quality_issue_id=%s AND q.instrument_id=%s''',(issue['data_quality_issue_id'],row['instrument_id']))
    exact,name,cik=c.fetchone()
   assert json.loads(exact)==issue['evidence']
   evidence=dict(sourceUri=uri,sourceSha256=hashlib.sha256(file.read_bytes()).hexdigest(),
    sourceObservedAt=datetime.now(timezone.utc).isoformat(),captureFormat='WEB_TOOL_RENDERED_RESPONSE_NOT_ORIGIN_BYTES',
    issueEvidence=exact,providerAction=action,providerSourceArtifactId=issue['source_artifact_id'],
    issuerName=name,issuerCik=cik,
    rationale=f'Individually inspected issuer dividend announcement/history confirms {action["symbol"]}, '
    f'USD {amount} per share, record date {record}, payment date {payment}. '
    'Provider terms and named issuer match. Accept only this identified cash-dividend issue; '
    'do not rewrite prices, symbol history, gross/net terms, timestamps or other issues.')
   if symbol=='FCX': evidence['amountReconciliation']='USD 0.075 base + USD 0.075 variable = USD 0.15; issuer announcement explicitly aggregates both.'
   if symbol in ('NI','VLO'):
    # The historical website table has an ex-date one day earlier. The dated
    # issuer announcement confirms record/payment/amount but not the ex-date.
    # Keep the issue blocked pending reconciliation of the conflicting dates.
    reports.append(dict(symbol=symbol,applied=False,reason='ISSUER_HISTORY_EX_DATE_CONFLICT'))
    continue
   if symbol=='MRSH':
    previous=json.loads(Path('Documentation/evidence/p2/closeout/review-MRSH.json').read_text())
    assert previous['instrumentId']==row['instrument_id']
    evidence['identityContinuityReviewId']=previous['reviewId']
    evidence['identityContinuityEvidenceSha256']=hashlib.sha256(Path('Documentation/evidence/p2/closeout/review-MRSH.json').read_bytes()).hexdigest()
   manifest=dict(reviewId=str(uuid5(NAMESPACE_URL,'p2-20261027-dividend:'+issue['data_quality_issue_id'])),
    kind='QUALITY_ISSUE',instrumentId=row['instrument_id'],factId=None,issueId=issue['data_quality_issue_id'],
    decision='ACCEPT',reviewer='codex-p2-issuer-source-review-20261027',evidence=evidence)
   path=target/f'review-{symbol}.json'
   if path.exists():
    # Observation timestamps belong to the retained first review, not retries.
    previous=json.loads(path.read_text());manifest['evidence']['sourceObservedAt']=previous['evidence']['sourceObservedAt']
    assert previous==manifest
   else: path.write_text(json.dumps(manifest,indent=2)+'\n')
   validate(manifest,file)
   recorded=apply(connection,manifest);assert apply(connection,manifest)==recorded
   reports.append(dict(symbol=symbol,applied=True,reviewId=manifest['reviewId'],recordedAt=recorded,sourceFile=str(file),idempotent=True))
report=dict(reviews=reports,applied=sum(r['applied'] for r in reports),published=False,
 policy='INDIVIDUAL_ISSUER_EVIDENCE; SERVER_RECORDED_AT; NO_PRICE_OR_TIMESTAMP_CHANGES')
(work/'reviews-applied.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
