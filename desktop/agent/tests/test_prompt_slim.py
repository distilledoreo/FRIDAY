import copy
import unittest
from unittest.mock import patch

from desktop.agent import prompt_slim
from desktop.agent.local_proxy import local_payload
from desktop.agent.prompt_slim import AGENTS_MARK, SLIM, digest, slim

ENV = ('Here is some useful information about the environment you are running in:\n<env>\n'
       '  Working directory: /synthetic/worktree\n  Today\'s date: Mon Oct 12 2026\n</env>\n')


def request(directory='/synthetic/worktree'):
    return {'messages': [{'role': 'system', 'content': 'Agent prompt.\n' + ENV.replace('/synthetic/worktree', directory) + 'Skills.'},
                         {'role': 'user', 'content': 'Synthetic task'}],
            'tools': [{'type': 'function', 'function': {'name': 'read', 'description': 'Original read text',
                'parameters': {'$schema': 'https://json-schema.org/draft/2020-12/schema', 'type': 'object',
                               'properties': {'offset': {'type': 'integer', 'minimum': 0, 'maximum': 9007199254740991}}}}},
                      {'type': 'function', 'function': {'name': 'task', 'description': 'Original task text\n' + AGENTS_MARK + '\n- explore: Synthetic'}},
                      {'type': 'function', 'function': {'name': 'friday-code-lookup_search_code', 'description': 'Unlisted tool'}}]}


class PromptSlimTests(unittest.TestCase):
    def known(self):
        return patch.dict(prompt_slim.ORIGINALS, {'read': digest('Original read text'), 'task': digest('Original task text\n')})

    def test_known_descriptions_are_rewritten_and_agent_list_is_kept(self):
        with self.known(): body = slim(request())
        tools = {t['function']['name']: t['function'] for t in body['tools']}
        self.assertEqual(tools['read']['description'], SLIM['read'])
        self.assertEqual(tools['task']['description'], SLIM['task'] + AGENTS_MARK + '\n- explore: Synthetic')
        self.assertEqual(tools['friday-code-lookup_search_code']['description'], 'Unlisted tool')
        self.assertEqual(tools['read']['parameters'], {'type': 'object', 'properties': {'offset': {'type': 'integer', 'minimum': 0}}})

    def test_changed_upstream_description_is_left_alone(self):
        body = request()
        body['tools'][0]['function']['description'] = 'OpenCode changed this'
        with self.known(): result = slim(body)
        self.assertEqual(result['tools'][0]['function']['description'], 'OpenCode changed this')

    def test_environment_moves_to_first_user_message_and_prefix_is_task_independent(self):
        with self.known(): first, second = slim(request('/one')), slim(request('/two'))
        self.assertEqual(first['messages'][0]['content'], 'Agent prompt.\nSkills.')
        self.assertEqual(first['messages'][0], second['messages'][0])
        self.assertEqual(first['tools'], second['tools'])
        self.assertTrue(first['messages'][1]['content'].startswith('Here is some useful information'))
        self.assertIn('Working directory: /one', first['messages'][1]['content'])
        self.assertTrue(first['messages'][1]['content'].endswith('\n\nSynthetic task'))

    def test_list_content_input_is_not_mutated_and_switch_disables(self):
        body = request()
        body['messages'][1]['content'] = [{'type': 'text', 'text': 'Synthetic task'}]
        before = copy.deepcopy(body)
        with self.known(): result = slim(body)
        self.assertEqual(body, before)
        self.assertEqual(result['messages'][1]['content'][0]['text'].splitlines()[1], '<env>')
        with patch.dict('os.environ', {'FRIDAY_LOCAL_PROMPT_SLIM': '0'}): self.assertIs(slim(body), body)

    def test_every_rewrite_has_a_recorded_original(self):
        self.assertEqual(set(prompt_slim.ORIGINALS), set(SLIM))

    def test_local_proxy_applies_it(self):
        with self.known(): body = local_payload(request())
        self.assertEqual(body['tools'][0]['function']['description'], SLIM['read'])
        self.assertNotIn('<env>', body['messages'][0]['content'])


if __name__ == '__main__': unittest.main()
