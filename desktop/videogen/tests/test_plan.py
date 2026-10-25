import unittest

from desktop.videogen.plan import Planner, blocks, frames_for, sizes, estimate, LEVELS


class PlanTests(unittest.TestCase):
    def test_frames_follow_the_model_grid(self):
        self.assertEqual(frames_for(4), 97)
        self.assertEqual(frames_for(10), 241)
        self.assertEqual(frames_for(26), 625)
        self.assertTrue(all((frames_for(s) - 1) % 8 == 0 for s in range(1, 30)))

    def test_measured_limits(self):
        # Rendered fully on two cards / ran out of memory.
        self.assertEqual(blocks(1600, 896, 97), 18200)
        self.assertEqual(blocks(640, 352, 625), 17380)
        self.assertGreater(blocks(640, 352, 673), 18200)
        self.assertGreater(blocks(1664, 928, 97), 18200)

    def test_sizes_keep_shape_on_the_grid(self):
        for aspect in ('16:9', '9:16', '1:1', '4:3', '3:4'):
            for w, h in sizes(aspect):
                self.assertEqual((w % 32, h % 32), (0, 0))
        self.assertIn((1024, 576), sizes('16:9'))
        self.assertIn((576, 1024), sizes('9:16'))

    def test_levels_run_from_fast_to_the_largest_that_fits(self):
        planner = Planner()
        levels = planner.levels(10, '16:9')
        self.assertEqual(len(levels), LEVELS)
        self.assertEqual((levels[0]['width'], levels[0]['height']), (640, 352))
        self.assertEqual((levels[-1]['width'], levels[-1]['height']), (1024, 576))
        work = [o['blocks'] for o in levels]
        self.assertEqual(work, sorted(work))
        self.assertTrue(all(o['blocks'] <= 18200 for o in levels))
        four = planner.levels(4, '16:9')
        self.assertEqual((four[-1]['width'], four[-1]['height']), (1600, 896))

    def test_the_card_that_keeps_chat_is_preferred(self):
        planner = Planner()
        fast = planner.levels(4, '16:9')[0]
        self.assertEqual(fast['placement'], 'single')
        self.assertFalse(fast['pauses_chat'])
        self.assertEqual(fast['chat_paused_seconds'], 0)
        best = planner.levels(4, '16:9')[-1]
        self.assertTrue(best['pauses_chat'])
        self.assertGreater(best['chat_paused_seconds'], best['estimate_seconds'])

    def test_durations_beyond_the_limit_have_no_levels(self):
        planner = Planner()
        self.assertTrue(planner.levels(26, '16:9'))
        self.assertEqual(planner.levels(27, '16:9'), [])
        self.assertEqual(planner.levels(1, '16:9'), [])
        with self.assertRaises(ValueError):
            planner.resolve(27, '16:9', 0)
        with self.assertRaises(ValueError):
            planner.resolve(4, '16:9', LEVELS)
        with self.assertRaises(ValueError):
            planner.levels(4, '2:1')

    def test_fast_uses_a_larger_size_when_it_still_meets_the_target_time(self):
        quick = {'placements': [{'name': 'single', 'pauses_chat': False, 'max_blocks': 20000, 'timing': [[1000, 10], [20000, 200]]}],
                 'fast_seconds': 100}
        fast = Planner(quick).levels(4, '16:9')[0]
        self.assertGreater(fast['blocks'], blocks(640, 352, 97))
        self.assertLessEqual(fast['estimate_seconds'], 100)

    def test_estimates_follow_measured_points(self):
        dual = Planner().placements[1]
        self.assertEqual(round(estimate(dual, 17380)), 2028)
        self.assertEqual(round(estimate(dual, 18200)), 2436)
        self.assertGreater(estimate(dual, 9000), estimate(dual, 3000))
        one = {'timing': [[2860, 410]]}
        self.assertEqual(round(estimate(one, 5720)), 820)

    def test_table_covers_every_shape_and_duration(self):
        table = Planner().table()
        self.assertEqual(table['durations'][0], 2)
        self.assertEqual(table['durations'][-1], 26)
        self.assertEqual(set(table['plans']), set(table['aspects']))
        self.assertEqual(len(table['plans']['16:9']['10']), LEVELS)


if __name__ == '__main__':
    unittest.main()
