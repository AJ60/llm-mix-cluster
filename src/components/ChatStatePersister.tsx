import React from 'react';
import { ChatTab, ChatTabProps } from './ChatTab';
import {
  STABLE_CHAT_PERSISTER_KEY,
  arePersistedChatPropsEqual,
} from '../domain/chatStateCache';

export { STABLE_CHAT_PERSISTER_KEY };

export interface ChatStatePersisterProps extends Omit<ChatTabProps, 'isTabVisible'> {
  visible: boolean;
}

/**
 * Memoized inner chat subtree keyed identically across all tab switches.
 * Because `visible` is NOT passed down into this subtree, changing `activeTab`
 * in App.tsx never re-renders ChatTab or any mounted chat turns.
 */
const MemoizedChatView = React.memo<Omit<ChatTabProps, 'isTabVisible'>>(
  (props) => <ChatTab {...props} />,
  arePersistedChatPropsEqual
);

MemoizedChatView.displayName = 'MemoizedChatView';

/**
 * Persistent wrapper container keeping the chat view mounted with a stable key
 * regardless of `activeTab`, while toggling visibility cleanly via CSS/ARIA.
 */
export const ChatStatePersister: React.FC<ChatStatePersisterProps> = ({
  visible,
  ...chatProps
}) => {
  return (
    <div
      key={STABLE_CHAT_PERSISTER_KEY}
      data-testid="chat-panel-container"
      data-persister-key={STABLE_CHAT_PERSISTER_KEY}
      data-active={visible ? 'true' : 'false'}
      aria-hidden={!visible}
      className={visible ? 'block' : 'hidden'}
    >
      <MemoizedChatView key={STABLE_CHAT_PERSISTER_KEY} {...chatProps} />
    </div>
  );
};
