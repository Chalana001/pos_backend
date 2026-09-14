package com.chala.posapp.barcode;

import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.ScaleBarcodeValueType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the decoding rules a shop's scale settings drive, so the unit, decimal
 * and prefix options cannot silently change what a printed label means.
 *
 * <p>The frontend mirrors these rules in {@code utils/scaleBarcode.js}; keep
 * the two in step.
 */
class ScaleBarcodeDecoderTest {

    /** A mutable stand-in for the record so each test tweaks one field. */
    private static final class Format {
        boolean enabled = true;
        String prefixes = "20";
        int prefixLength = 2;
        int itemCodeLength = 5;
        int valueLength = 5;
        ScaleBarcodeValueType valueType = ScaleBarcodeValueType.WEIGHT;
        MeasurementUnit weightUnit = MeasurementUnit.G;
        int valueDecimals = 0;
        boolean stripLeadingZeros = false;
        boolean hasCheckDigit = true;

        ScaleBarcodeFormat build() {
            return new ScaleBarcodeFormat(enabled, prefixes, prefixLength, itemCodeLength, valueLength,
                    valueType, weightUnit, valueDecimals, stripLeadingZeros, hasCheckDigit);
        }
    }

    /** Appends the EAN-13 mod-10 check digit so tests never hand-compute it. */
    private static String withCheck(String payload) {
        return payload + ScaleBarcodeDecoder.computeEan13CheckDigit(payload);
    }

    private static DecodedScaleBarcode decode(String barcode, Format f) {
        Optional<DecodedScaleBarcode> decoded = ScaleBarcodeDecoder.tryDecode(barcode, f.build());
        assertTrue(decoded.isPresent(), "expected " + barcode + " to decode");
        return decoded.get();
    }

    private static boolean decodes(String barcode, Format f) {
        return ScaleBarcodeDecoder.tryDecode(barcode, f.build()).isPresent();
    }

    @Test
    void wholeGramsDecodeAsGrams() {
        DecodedScaleBarcode decoded = decode(withCheck("20" + "12345" + "01234"), new Format());
        assertEquals("12345", decoded.itemCode());
        assertEquals(ScaleBarcodeValueType.WEIGHT, decoded.valueType());
        assertEquals(0, new BigDecimal("1234").compareTo(decoded.value()));
    }

    @Test
    void kilogramsWithImpliedDecimalsBecomeGrams() {
        Format f = new Format();
        f.weightUnit = MeasurementUnit.KG;
        f.valueDecimals = 3;
        DecodedScaleBarcode decoded = decode(withCheck("20" + "12345" + "01234"), f);
        assertEquals(0, new BigDecimal("1234").compareTo(decoded.value()));

        // Two decimals: 00125 is 1.25 kg, which V30's integer-grams rule read as 125 g.
        f.valueDecimals = 2;
        decoded = decode(withCheck("20" + "12345" + "00125"), f);
        assertEquals(0, new BigDecimal("1250").compareTo(decoded.value()));
    }

    @Test
    void gramsWithDecimalsKeepTheFractionForTheCallerToRound() {
        Format f = new Format();
        f.valueDecimals = 1;
        DecodedScaleBarcode decoded = decode(withCheck("20" + "12345" + "12345"), f);
        assertEquals(0, new BigDecimal("1234.5").compareTo(decoded.value()));
    }

    @Test
    void priceUsesTheConfiguredDecimals() {
        Format f = new Format();
        f.valueType = ScaleBarcodeValueType.PRICE;
        f.valueDecimals = 2;
        DecodedScaleBarcode decoded = decode(withCheck("20" + "12345" + "61704"), f);
        assertEquals(ScaleBarcodeValueType.PRICE, decoded.valueType());
        assertEquals(0, new BigDecimal("617.04").compareTo(decoded.value()));

        // Whole rupees, no implied decimals.
        f.valueDecimals = 0;
        decoded = decode(withCheck("20" + "12345" + "61704"), f);
        assertEquals(0, new BigDecimal("61704").compareTo(decoded.value()));
    }

    @Test
    void prefixListMatchesAnyEntryAndRejectsOthers() {
        Format f = new Format();
        f.prefixes = "20,21,22";
        assertTrue(decodes(withCheck("21" + "12345" + "01234"), f));
        assertTrue(decodes(withCheck("22" + "12345" + "01234"), f));
        assertTrue(!decodes(withCheck("23" + "12345" + "01234"), f));
    }

    @Test
    void blankPrefixAcceptsAnyLeadingCharacters() {
        Format f = new Format();
        f.prefixes = null;
        assertTrue(decodes(withCheck("99" + "12345" + "01234"), f));
    }

    @Test
    void letteredPrefixAndItemCodeDecodeWithoutACheckDigit() {
        // A scale whose PLU is "NS12" and which prints it literally, followed by
        // four weight digits in grams: "NS121250".
        Format f = new Format();
        f.prefixes = "NS";
        f.prefixLength = 2;
        f.itemCodeLength = 2;
        f.valueLength = 4;
        f.hasCheckDigit = false;
        DecodedScaleBarcode decoded = decode("NS121250", f);
        assertEquals("12", decoded.itemCode());
        assertEquals(0, new BigDecimal("1250").compareTo(decoded.value()));

        // Lower case from the scanner still matches an upper-case prefix.
        assertTrue(decodes("ns121250", f));

        // Or the whole "NS12" is the item code with no prefix at all.
        f.prefixes = null;
        f.prefixLength = 0;
        f.itemCodeLength = 4;
        decoded = decode("NS121250", f);
        assertEquals("NS12", decoded.itemCode());
    }

    @Test
    void lettersInThePayloadAcceptTheCheckDigitUnverified() {
        // A real scale prints "NS1 00001 00050 2": lettered prefix, then a
        // trailing check digit of its own making. EAN-13 mod-10 has no meaning
        // over letters, so the digit is taken as present but not verified and
        // the length rule carries the detection.
        Format f = new Format();
        f.prefixes = "NS1";
        f.prefixLength = 3;
        DecodedScaleBarcode decoded = decode("NS100001000502", f);
        assertEquals("00001", decoded.itemCode());
        assertEquals(0, new BigDecimal("50").compareTo(decoded.value()));

        // The same layout with the check digit turned off is one character shorter.
        assertTrue(!decodes("NS100001000502", withoutCheck(f)));
    }

    private static Format withoutCheck(Format f) {
        f.hasCheckDigit = false;
        return f;
    }

    @Test
    void lettersInTheValueSegmentNeverDecode() {
        Format f = new Format();
        f.hasCheckDigit = false;
        assertTrue(!decodes("20" + "12345" + "12A45", f));
    }

    @Test
    void leadingZerosInTheItemCodeAreStrippedOnlyWhenAsked() {
        Format f = new Format();
        assertEquals("00123", decode(withCheck("20" + "00123" + "01234"), f).itemCode());

        f.stripLeadingZeros = true;
        assertEquals("123", decode(withCheck("20" + "00123" + "01234"), f).itemCode());
        // Never strips to nothing.
        assertEquals("0", decode(withCheck("20" + "00000" + "01234"), f).itemCode());
    }

    @Test
    void wrongLengthOrBadCheckDigitIsNotAScaleBarcode() {
        Format f = new Format();
        String good = withCheck("20" + "12345" + "01234");
        assertTrue(!decodes(good.substring(0, 12), f), "one short");
        assertTrue(!decodes(good + "0", f), "one long");

        char last = good.charAt(12);
        char wrong = last == '9' ? '0' : (char) (last + 1);
        assertTrue(!decodes(good.substring(0, 12) + wrong, f), "bad check digit");
    }

    @Test
    void noCheckDigitShortensTheExpectedLength() {
        Format f = new Format();
        f.hasCheckDigit = false;
        assertTrue(decodes("20" + "12345" + "01234", f));
        assertTrue(!decodes(withCheck("20" + "12345" + "01234"), f));
    }

    @Test
    void twelveDigitValuesFitWhereIntegerParsingOverflowed() {
        Format f = new Format();
        f.valueLength = 12;
        f.hasCheckDigit = false;
        DecodedScaleBarcode decoded = decode("20" + "12345" + "999999999999", f);
        assertEquals(0, new BigDecimal("999999999999").compareTo(decoded.value()));
    }

    @Test
    void disabledOrMissingFormatNeverDecodes() {
        Format f = new Format();
        f.enabled = false;
        assertTrue(!decodes(withCheck("20" + "12345" + "01234"), f));
        assertTrue(ScaleBarcodeDecoder.tryDecode(withCheck("20" + "12345" + "01234"), null).isEmpty());
        assertTrue(ScaleBarcodeDecoder.tryDecode(withCheck("20" + "12345" + "01234"), ScaleBarcodeFormat.DISABLED).isEmpty());
        assertTrue(!decodes(null, new Format()));
        assertTrue(!decodes("", new Format()));
    }
}
