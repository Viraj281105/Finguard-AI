# Statement fixtures

These CSV files are **synthetic**. They were written by hand to follow the column layouts documented in
`ingestion/parser/*Parser.java`; they are not exports from real bank accounts, and no real merchants,
account numbers or balances are used. Passing tests therefore show the parsers implement the documented
layouts, not that they handle every variation real banks produce. Add anonymised real statements here
(and tests for them) as they become available.
