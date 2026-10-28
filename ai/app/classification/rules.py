"""The rule table of the baseline classifier (version ``RULESET_VERSION``).

Every rule is one regular expression over *normalised* text (see ``normalize.py``: lowercase, tokens
separated by single spaces, reference numbers removed) and is matched on token boundaries, so ``rent``
does not fire inside ``current``. A rule carries:

* ``strength`` - how much evidence the pattern is. It is a coarse tier, **not a probability**:
  ``STRONG`` (unambiguous merchant or phrase), ``MEDIUM`` (usually right, can be a different business
  with the same name) and ``WEAK`` (a generic keyword; always flagged for review).
* ``priority`` - which rule wins when several match. Specific beats generic: ``swiggy instamart``
  (80) beats ``swiggy`` (60); ``school fees`` -> education (60) beats a bare ``fees`` (30).

Business rules that keep money movements out of ordinary spending sit at priority 100:
credit-card bill payments, refunds/reversals/cashback, own-account transfers. Whether a category
suits the direction of a transaction is decided by the taxonomy (``taxonomy.allows``), not here.

This is a hand-written baseline for Indian retail-banking narrations. It is not exhaustive, and its
coverage and precision on real data are unknown (see ``evaluation/report.md`` for synthetic-data
measurements and their limits).
"""

import re
from dataclasses import dataclass

from app.classification.taxonomy import Category

RULESET_VERSION = "rules-1.0.0"

STRONG = 0.90
MEDIUM = 0.75
WEAK = 0.55

P_BUSINESS = 100
P_SPECIFIC = 80
P_MERCHANT = 60
P_KEYWORD = 40
P_GENERIC = 30
P_CHANNEL = 20


@dataclass(frozen=True)
class Rule:
    id: str
    category: Category
    label: str
    pattern: re.Pattern[str]
    strength: float
    priority: int
    reason: str = ""
    """Only used by explicit-abstention rules (category ``UNCATEGORIZED``): why we decline."""


def _slug(pattern: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", pattern.lower()).strip("_")[:40]


def _compile(pattern: str) -> re.Pattern[str]:
    return re.compile(rf"(?<![a-z0-9])(?:{pattern})(?![a-z0-9])")


def _rules(
    category: Category,
    priority: int,
    *,
    strong: tuple[str, ...] = (),
    medium: tuple[str, ...] = (),
    weak: tuple[str, ...] = (),
    reason: str = "",
) -> list[Rule]:
    out: list[Rule] = []
    for strength, patterns in ((STRONG, strong), (MEDIUM, medium), (WEAK, weak)):
        for pattern in patterns:
            out.append(
                Rule(
                    id=f"{category.value.lower()}.{_slug(pattern)}",
                    category=category,
                    label=pattern,
                    pattern=_compile(pattern),
                    strength=strength,
                    priority=priority,
                    reason=reason,
                )
            )
    return out


C = Category

RULES: list[Rule] = [
    # ---- Money that is not ordinary spending (priority 100) ------------------------------------
    *_rules(
        C.CARD_PAYMENTS,
        P_BUSINESS,
        strong=(
            r"credit card (payment|pmt|bill|autopay|auto pay|repayment|dues)",
            r"(cc|card) (payment|bill|autopay|auto pay|repayment)",
            r"cred club",
            r"cardpay",
        ),
    ),
    *_rules(
        C.CARD_PAYMENTS, 90, medium=(r"cred", r"credit card"),
    ),
    *_rules(
        C.REFUNDS,
        P_BUSINESS,
        strong=(r"refund(ed)?", r"reversal", r"reversed", r"chargeback", r"rev txn"),
        medium=(r"cash ?back", r"credit adjustment"),
    ),
    *_rules(
        C.TRANSFERS,
        P_BUSINESS,
        strong=(
            r"self transfer",
            r"own account",
            r"to self",
            r"sweep (in|out)",
            r"(savings|salary|current) account transfer",
            r"transfer to (savings|current|own|self)",
            r"fund transfer to (savings|current)",
        ),
    ),
    # ---- Fees whose wording is unambiguous (beat the broader categories) ------------------------
    *_rules(
        C.FEES,
        90,
        strong=(
            (
                r"(annual|joining|renewal|late payment|late|overlimit|over limit|processing|"
                r"convenience|sms|debit card|atm|nach return|cheque return|cheque book|dishonour|"
                r"demat amc|amc|min(imum)? bal(ance)?|non maintenance|account maintenance) "
                r"(fee|fees|charge|charges|chrgs|chg)"
            ),
            r"(i|c|s)?gst on",
        ),
    ),
    # ---- Income ---------------------------------------------------------------------------------
    *_rules(
        C.INCOME,
        P_SPECIFIC,
        strong=(r"salary", r"sal credit", r"payroll", r"stipend", r"wages"),
        medium=(r"dividend", r"bonus", r"interest (paid|credit(ed)?|capitalised|capitalized)", r"credit interest", r"int pd", r"sb int",
                r"savings interest", r"int credit"),
    ),
    *_rules(C.INCOME, P_KEYWORD, weak=(r"freelance", r"consulting", r"commission")),
    # ---- Insurance -------------------------------------------------------------------------------
    *_rules(
        C.INSURANCE,
        P_SPECIFIC,
        strong=(
            r"hdfc life", r"sbi life", r"max life", r"icici pru(dential)? life", r"ipru ?life",
            r"tata aia", r"bajaj allianz", r"star health", r"care health", r"niva bupa",
            r"new india assurance", r"acko", r"digit insurance", r"go digit", r"policybazaar",
            r"policy bazaar", r"hdfc ergo", r"royal sundaram", r"lic of india", r"lic premium",
            r"insurance",
        ),
        medium=(r"lic",),
        weak=(r"premium",),
    ),
    # ---- Loans -----------------------------------------------------------------------------------
    *_rules(
        C.LOAN_PAYMENTS,
        P_SPECIFIC,
        strong=(
            r"emi", r"loan (repayment|instalment|installment|emi|payment|recovery|a c|account)",
            r"(home|personal|car|vehicle|auto|education|gold|two wheeler) loan",
            r"lic housing", r"bajaj finance", r"bajaj finserv", r"tata capital", r"fullerton",
            r"muthoot", r"manappuram", r"home credit", r"moneyview", r"kreditbee",
        ),
        weak=(r"loan",),
    ),
    # ---- Investments -----------------------------------------------------------------------------
    *_rules(
        C.INVESTMENTS,
        P_SPECIFIC,
        strong=(
            r"zerodha", r"groww", r"upstox", r"angel one", r"angel ?broking", r"icici ?direct",
            r"hdfc securities", r"hdfc sec", r"kotak securities", r"5paisa", r"paytm money",
            r"kuvera", r"smallcase", r"et ?money", r"ind ?money", r"motilal oswal", r"sharekhan",
            r"nse clearing", r"nsccl", r"iccl", r"indian clearing", r"bse clearing", r"nsdl",
            r"cdsl", r"bse star ?mf", r"mf utility", r"mutual funds?", r"mf", r"sip",
            r"systematic investment", r"nippon india", r"mirae asset", r"parag parikh", r"ppfas",
            r"franklin templeton", r"national pension", r"nps", r"ppf", r"public provident",
            r"sovereign gold", r"sgb", r"gold bond", r"digital gold",
            r"fixed deposit", r"recurring deposit", r"fd (booking|creation|opening|renewal)",
            r"rd (installment|instalment|inst)", r"demat",
        ),
        medium=(r"epf", r"provident fund", r"ipo", r"stock broking", r"broking", r"trading"),
        weak=(r"stocks?", r"equity", r"bonds?"),
    ),
    # ---- Subscriptions (specific brands beat the retailers that host them) ----------------------
    *_rules(
        C.SUBSCRIPTIONS,
        P_SPECIFIC,
        strong=(
            r"netflix", r"spotify", r"youtube (premium|music)", r"prime video", r"primevideo",
            r"amazon prime", r"prime membership", r"hotstar", r"jiohotstar", r"disney", r"sonyliv",
            r"sony liv", r"zee5", r"voot", r"altbalaji", r"alt balaji", r"apple (com )?bill",
            r"apple services", r"itunes", r"icloud", r"google (one|storage|workspace)", r"g suite",
            r"microsoft 365", r"office 365", r"microsoft subscription", r"adobe", r"notion",
            r"chatgpt", r"openai", r"anthropic", r"claude ai", r"github", r"gitlab", r"dropbox",
            r"evernote", r"1password", r"lastpass", r"nordvpn", r"expressvpn", r"linkedin premium",
            r"audible", r"kindle unlimited", r"gaana", r"jiosaavn", r"wynk", r"canva", r"figma",
            r"substack", r"patreon", r"headspace",
        ),
        medium=(r"subscription", r"slack", r"zoom"),
        weak=(r"membership",),
    ),
    # ---- Groceries -------------------------------------------------------------------------------
    *_rules(
        C.GROCERIES,
        P_SPECIFIC,
        strong=(
            r"swiggy instamart",
            r"instamart",
            r"zomato hyperpure",
            r"hyperpure",
            r"amazon (pay )?(fresh|grocery|groceries)",
            r"(big ?bazaar|star baza?ar|hypercity|supermarket|hypermarket) (groceries|grocery)",
        ),
    ),
    *_rules(
        C.GROCERIES,
        P_MERCHANT,
        strong=(
            r"big ?basket", r"blinkit", r"grofers", r"zepto", r"d ?mart", r"avenue supermarts",
            r"jio ?mart", r"reliance fresh", r"reliance smart", r"more retail", r"more megastore",
            r"spencer s?", r"nature s basket", r"natures basket", r"star baza?ar", r"licious", r"hypercity",
            r"fresh ?to ?home", r"milkbasket", r"country delight", r"ratnadeep", r"easy ?day",
            r"lulu hypermarket", r"hypermarket", r"supermarket", r"super market",
        ),
        medium=(r"grocery", r"groceries", r"kirana", r"provision store", r"vegetables?"),
    ),
    *_rules(C.GROCERIES, P_KEYWORD, weak=(r"fruits?", r"dairy", r"milk", r"general store")),
    # ---- Dining ----------------------------------------------------------------------------------
    *_rules(
        C.DINING,
        P_MERCHANT,
        strong=(
            r"dominos?", r"domino s", r"mcdonald s?", r"kfc", r"burger king", r"pizza hut",
            r"starbucks", r"cafe coffee day", r"ccd", r"haldirams?", r"haldiram s", r"chaayos", r"chai point",
            r"barbeque nation", r"behrouz", r"faasos", r"eatsure", r"box8", r"dineout",
            r"baskin robbins", r"theobroma", r"third wave", r"blue tokai", r"dunkin", r"taco bell",
            r"wendy s",
        ),
        medium=(r"zomato", r"swiggy", r"restaurant", r"restaurants", r"bakery", r"biryani", r"ice creams?",
                r"dhaba", r"eatery", r"bistro", r"pizzeria"),
    ),
    *_rules(C.DINING, P_KEYWORD, weak=(r"cafe", r"tiffin", r"food court", r"canteen")),
    # ---- Transport -------------------------------------------------------------------------------
    *_rules(C.TRANSPORT, P_SPECIFIC, strong=(r"fastag",)),  # beats the generic 'recharge'
    *_rules(
        C.TRANSPORT,
        P_MERCHANT,
        strong=(
            r"uber( india)?", r"ola cabs", r"olacabs", r"ani technologies", r"rapido", r"blusmart",
            r"namma yatri", r"quick ?ride", r"indian railways", r"irctc", r"red ?bus", r"abhibus",
            r"ksrtc", r"msrtc", r"bmtc", r"tsrtc", r"dmrc", r"delhi metro", r"bmrcl",
            r"namma metro", r"mumbai metro", r"netc", r"nhai", r"toll plaza",
            r"hpcl", r"bpcl", r"iocl", r"indian ?oil", r"bharat petroleum", r"hindustan petroleum",
            r"nayara", r"reliance bp", r"jio bp", r"petrol pump", r"filling station", r"indigo",
            r"interglobe", r"air india( express)?", r"vistara", r"spicejet", r"akasa air",
            r"airasia", r"make ?my ?trip", r"goibibo", r"ixigo", r"cleartrip", r"easemytrip",
        ),
        medium=(r"ola", r"petrol", r"diesel", r"fuel", r"toll", r"parking", r"railway",
                r"railways", r"metro", r"shell", r"yatra", r"meru"),
    ),
    # ---- Housing ---------------------------------------------------------------------------------
    *_rules(
        C.HOUSING,
        P_MERCHANT,
        strong=(r"no ?broker", r"nestaway", r"housing society", r"society maintenance",
                r"property tax", r"municipal tax", r"house rent", r"rent payment", r"monthly rent",
                r"pg rent", r"landlord"),
        medium=(r"rent", r"rental", r"apartment owners", r"rwa", r"brokerage", r"security deposit"),
    ),
    # ---- Utilities -------------------------------------------------------------------------------
    *_rules(
        C.UTILITIES,
        P_MERCHANT,
        strong=(
            r"electricity", r"bescom", r"mseb", r"msedcl", r"mahadiscom", r"tneb", r"tangedco",
            r"kseb", r"bses", r"tata power", r"adani electricity", r"torrent power", r"cesc",
            r"wbsedcl", r"uppcl", r"pspcl", r"dhbvn", r"uhbvn", r"apspdcl", r"tspdcl", r"tsspdcl",
            r"jvvnl", r"bijli", r"water (bill|board)", r"bwssb", r"jal board", r"lpg",
            r"mahanagar gas", r"indane", r"bharat ?gas", r"hp gas", r"igl", r"indraprastha gas",
            r"gujarat gas", r"adani gas", r"piped gas", r"gas (bill|cylinder|booking|agency)",
            r"act fibernet", r"hathway", r"tikona", r"excitel", r"spectranet", r"tata play",
            r"tata sky", r"dish tv", r"d2h", r"sun direct", r"airtel digital", r"dth",
            r"vodafone idea", r"vi (prepaid|postpaid|recharge)", r"bsnl", r"mtnl",
            r"mobile (bill|recharge)", r"phone bill", r"internet bill", r"broadband",
            r"recharge", r"postpaid", r"prepaid",
        ),
        medium=(r"airtel", r"jio", r"vodafone", r"wifi"),
        weak=(r"bbps",),
    ),
    # ---- Healthcare ------------------------------------------------------------------------------
    *_rules(
        C.HEALTHCARE,
        P_MERCHANT,
        strong=(
            r"apollo (pharmacy|hospitals?|clinic|diagnostics)", r"pharm ?easy", r"1 ?mg",
            r"tata 1mg", r"netmeds", r"medplus", r"practo", r"medlife", r"medibuddy", r"lal path ?labs?",
            r"thyrocare", r"metropolis", r"dr lal", r"fortis", r"max healthcare", r"manipal",
            r"narayana health", r"aster",
            r"pharmacy", r"chemist", r"hospital", r"hospitals", r"clinic", r"diagnostics?",
            r"pathology", r"dental", r"dentist",
        ),
        medium=(r"apollo", r"medical stores?", r"medicals?", r"optical", r"eye care", r"doctor",
                r"physio(therapy)?"),
    ),
    # ---- Shopping --------------------------------------------------------------------------------
    *_rules(
        C.SHOPPING,
        P_SPECIFIC,
        strong=(r"amazon (pay )?shopping",),
    ),
    *_rules(
        C.SHOPPING,
        P_MERCHANT,
        strong=(
            r"flipkart", r"myntra", r"ajio", r"nykaa", r"meesho", r"snapdeal", r"tata ?cliq",
            r"shopsy", r"firstcry", r"lenskart", r"pepperfry", r"urban ladder", r"ikea", r"croma",
            r"reliance digital", r"vijay sales", r"decathlon", r"pantaloons", r"westside",
            r"shoppers stop", r"max fashion", r"zudio", r"zara", r"uniqlo", r"purplle",
            r"bewakoof", r"tanishq", r"caratlane", r"malabar gold", r"kalyan jewellers",
            r"big bazaar", r"apple store",
        ),
        medium=(r"amazon", r"amzn( mktp)?", r"lifestyle", r"bata", r"nike", r"adidas", r"puma",
                r"titan"),
    ),
    *_rules(
        C.SHOPPING, P_KEYWORD,
        weak=(r"fashion", r"garments", r"clothing", r"electronics", r"jewellers", r"jewellery",
              r"footwear", r"mall"),
    ),
    # ---- Education -------------------------------------------------------------------------------
    *_rules(
        C.EDUCATION,
        P_MERCHANT,
        strong=(
            r"byjus?", r"byju s", r"unacademy", r"udemy", r"coursera", r"upgrad", r"edx", r"vedantu",
            r"physics ?wallah", r"simplilearn", r"great learning", r"toppr", r"testbook", r"nptel",
            r"exam fee", r"school fees?", r"tuition fees?", r"college fees?", r"hostel fees?",
            r"admission fees?", r"tuition", r"coaching", r"school", r"college", r"university",
        ),
        medium=(r"academy", r"institute", r"admission"),
    ),
    # ---- Entertainment ---------------------------------------------------------------------------
    *_rules(
        C.ENTERTAINMENT,
        P_MERCHANT,
        strong=(
            r"book ?my ?show", r"pvr", r"inox", r"cinepolis", r"carnival cinemas", r"miraj cinemas",
            r"movie", r"cinema", r"multiplex", r"playstation", r"psn", r"xbox", r"nintendo",
            r"epic games", r"dream ?11", r"my11circle", r"winzo", r"wonderla", r"imagica",
            r"water ?park", r"amusement", r"paytm insider", r"concert",
        ),
        medium=(r"steam( games)?", r"mpl", r"theatre", r"theater", r"brewery", r"brewing co",
                r"taproom", r"gaming"),
    ),
    *_rules(C.ENTERTAINMENT, P_KEYWORD, weak=(r"game", r"games", r"arcade", r"pub")),
    # ---- Fees, generic wording (below every merchant/category rule) -----------------------------
    *_rules(
        C.FEES,
        P_KEYWORD,
        medium=(r"bank charges", r"service charges?", r"penalty", r"penal charges?",
                r"(i|c|s)gst", r"stamp duty", r"charges", r"chrgs"),
    ),
    *_rules(C.FEES, P_GENERIC, weak=(r"fees?",)),
    # ---- Transfers by channel: the narration says how, not why (abstain to avoid guessing purpose)
    *_rules(
        C.UNCATEGORIZED,
        P_CHANNEL,
        strong=(r"neft", r"imps", r"rtgs", r"fund transfer", r"fund trf", r"tpt", r"trf"),
        reason="Payment channel (NEFT/IMPS/RTGS/TRF) indicates transfer rail, not financial purpose or own-account movement; review required",
    ),
    # ---- Explicit abstentions: well-known narrations that cannot be categorised honestly --------
    *_rules(
        C.UNCATEGORIZED,
        70,
        strong=(r"amazon pay",),
        reason="Wallet or gateway top-up; the underlying purchase is unknown",
    ),
    *_rules(
        C.UNCATEGORIZED,
        P_GENERIC,
        strong=(
            r"atm (wdl|cash|withdrawal|debit)",
            r"atm",
            r"cash (wdl|withdrawal|deposit)",
            r"cash dep",
            r"nfs atm",
            r"matm",
        ),
        reason="Cash movement; what the cash was used for is unknown",
    ),
    *_rules(
        C.UNCATEGORIZED,
        P_GENERIC,
        strong=(r"bill ?desk", r"paytm", r"phone ?pe", r"google pay", r"gpay", r"razorpay",
                r"payu", r"cashfree", r"ccavenue"),
        reason="Payment gateway or wallet; the merchant behind it is unknown",
    ),
]
