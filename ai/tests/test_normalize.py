from app.classification.normalize import normalize


def test_lowercases_and_collapses_separators():
    assert normalize("Swiggy*Order__#12345678").text == "swiggy order"


def test_upi_handle_suffix_is_dropped_but_payee_kept():
    assert normalize("UPI-SWIGGY-swiggy@icici-ICIC0000123-412345678901-Payment").text == (
        "upi swiggy swiggy payment"
    )
    assert normalize("rapido@ybl").text == "rapido"


def test_reference_numbers_and_masked_cards_are_removed():
    assert normalize("POS 4567XXXXXX1234 AMAZON PAY INDIA 98765432").text == "pos amazon pay india"


def test_accents_are_folded():
    assert normalize("Café Coffee Day").text == "cafe coffee day"


def test_channel_is_detected():
    assert normalize("NEFT-ACME-SALARY").channel == "neft"
    assert normalize("UPI/ram@oksbi").channel == "upi"
    assert normalize("Random shop").channel is None


def test_empty_and_none_are_empty():
    assert normalize(None).is_empty
    assert normalize("   ").is_empty
    assert normalize("1234567890").is_empty
