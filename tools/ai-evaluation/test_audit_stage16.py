"""Fast parser/classification regression probes; no Forge/Ollama dependency."""
import unittest
from audit_stage16 import parse_script, classify


class AuditParserTest(unittest.TestCase):
    def test_distinguishes_faces_and_combined_markers(self):
        names, markers, _, _ = parse_script('Name:Front\nAI:RemoveDeck:All\nName:Back\nAI:RemoveDeck:Random\n')
        self.assertEqual(names, ['Front', 'Back'])
        self.assertEqual(markers, [(2, 'AI:RemoveDeck:All'), (4, 'AI:RemoveDeck:Random')])

    def test_variant_and_nested_apis_retained(self):
        _, _, abilities, apis = parse_script('Name:A\nVariant:C:A:AB$ Pump | Cost$ 1\nSVar:Next:DB$ Draw\n')
        self.assertEqual(apis, ['Draw', 'Pump'])
        self.assertEqual(len(abilities), 2)

    def test_random_marker_does_not_prove_runtime_failure(self):
        self.assertEqual(classify('Unknown', False, [], False, False)[0], 'H')

    def test_all_marker_is_refusal_not_proven_missing_rules(self):
        category, score, _, reason = classify('Unknown', True, [], False, False)
        self.assertEqual((category, score), ('D', 'LOW'))
        self.assertIn('unverified', reason)

    def test_explicit_default_variant_is_rules_gap(self):
        self.assertEqual(classify('Unknown', True, [], False, True)[:2], ('F', 'NONE'))

    def test_scoped_never_payment_marker_is_preserved(self):
        _, markers, _, _ = parse_script('Name:A\nA:SP$ Draw | UnlessAI$ Never | NumCards$ 1\n')
        self.assertEqual(len(markers), 1)

    def test_verified_safe_pump_requires_candidate_work(self):
        self.assertEqual(classify('Earthbrawn', True, [], False, False)[:2], ('B', 'MEDIUM'))

    def test_counter_exception_is_distinct_from_generic_warning(self):
        self.assertEqual(classify('Swan Song', True, [], False, False)[:2], ('G', 'HIGH'))


if __name__ == '__main__':
    unittest.main()
