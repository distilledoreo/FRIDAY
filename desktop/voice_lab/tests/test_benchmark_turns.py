import unittest

from desktop.voice_lab.benchmark_turns import playback_time, spoken_chunks


class TurnTimingTests(unittest.TestCase):
    def test_sentences_wait_for_boundary_and_combine_short_acknowledgment(self):
        self.assertEqual(spoken_chunks('The answer is forty-five.')[0], [])
        self.assertEqual(spoken_chunks('The answer is forty-five. Next')[0], ['The answer is forty-five.'])
        self.assertEqual(spoken_chunks('Sure. The answer is')[0], [])
        self.assertEqual(spoken_chunks('Sure. The answer is forty-five. Next')[0],
                         ['Sure. The answer is forty-five.'])
        self.assertEqual(spoken_chunks('Forty-five.', final=True)[0], ['Forty-five.'])

    def test_playback_uses_queue_and_waits_for_late_packet(self):
        events = [{'audio_offset_s': 0, 'arrival_s': .2, 'duration_s': 1},
                  {'audio_offset_s': 1, 'arrival_s': .3, 'duration_s': 1},
                  {'audio_offset_s': 2, 'arrival_s': 3, 'duration_s': 1}]
        self.assertAlmostEqual(playback_time(events, .5), .76)
        self.assertAlmostEqual(playback_time(events, 1.5), 1.76)
        self.assertAlmostEqual(playback_time(events, 2.5), 3.56)
        with self.assertRaises(ValueError):
            playback_time(events, 4)

    def test_final_flush_preserves_answer_after_filler(self):
        text = 'Sure. What is the first thing on your list?'
        self.assertEqual(spoken_chunks(text), ([], 0))
        chunks, consumed = spoken_chunks(text, final=True)
        self.assertEqual(chunks, ['Sure.', 'What is the first thing on your list?'])
        self.assertEqual(consumed, len(text))

    def test_streamed_boundary_does_not_repeat_already_queued_sentence(self):
        text = 'Try the Pomodoro Technique. Work for 25 minutes.'
        chunks, consumed = spoken_chunks(text)
        self.assertEqual(chunks, ['Try the Pomodoro Technique.'])
        self.assertEqual(spoken_chunks(text, consumed, final=True)[0], ['Work for 25 minutes.'])

    def test_clause_mode_preserves_short_phrases_and_emits_complete_long_clause(self):
        self.assertEqual(spoken_chunks('First, pick one task', clauses=True)[0], [])
        text = 'Write down the three things you need to finish today, then pick one.'
        chunks, consumed = spoken_chunks(text, clauses=True)
        self.assertEqual(chunks, ['Write down the three things you need to finish today,'])
        self.assertEqual(spoken_chunks(text, consumed, final=True, clauses=True)[0], ['then pick one.'])
        self.assertEqual(spoken_chunks(text)[0], [])

    def test_clause_mode_does_not_split_adjectives_or_lists(self):
        text = 'Try the Pomodoro technique, breaking work into short, timed intervals, which helps focus.'
        chunks, consumed = spoken_chunks(text, clauses=True)
        self.assertEqual(chunks, ['Try the Pomodoro technique, breaking work into short, timed intervals,'])
        self.assertEqual(spoken_chunks(text, consumed, final=True, clauses=True)[0], ['which helps focus.'])


if __name__ == '__main__':
    unittest.main()
