package consensus;

import common.ClientMessage;

public record ClientRequest(int id, ClientMessage requestMessage) {
}
