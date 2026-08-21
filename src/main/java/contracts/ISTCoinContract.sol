pragma solidity >=0.7.0 <0.9.0;

contract ISTCoin {
    string public name = "IST Coin";
    string public symbol = "IST";
    uint8 public decimals = 2;
    uint256 public totalSupply = 100000000 * 10**uint256(decimals);

    mapping(address => uint256) public balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;

    constructor() {
        balanceOf[msg.sender] = totalSupply;
    }

    function transfer(address _to, uint256 _value) public returns (bool success) {
        require(balanceOf[msg.sender] >= _value, "Insufficient balance");
        
        balanceOf[msg.sender] -= _value;
        balanceOf[_to] += _value;
        emit Transfer(msg.sender, _to, _value);
        return true;
    }

    // instead of approve(), force allowance to 0 first.
    // the user must pass what they think the current allowance is. 
    // if the malicious node already stole it, the require() fails and protects the user
    function safeApprove(address _spender, uint256 _currentValue, uint256 _newValue) public returns (bool success) {
        require(allowance[msg.sender][_spender] == _currentValue, "Approval Frontrunning attack detected.");
        
        allowance[msg.sender][_spender] = _newValue;
        emit Approval(msg.sender, _spender, _newValue);
        return true;
    }

    function transferFrom(address _from, address _to, uint256 _value) public returns (bool success) {
        require(balanceOf[_from] >= _value, "Insufficient balance");
        require(allowance[_from][msg.sender] >= _value, "Insufficient allowance");

        balanceOf[_from] -= _value;
        allowance[_from][msg.sender] -= _value;
        balanceOf[_to] += _value;
        emit Transfer(_from, _to, _value);
        return true;
    }

    event Transfer(address indexed from, address indexed to, uint256 value);
    event Approval(address indexed owner, address indexed spender, uint256 value);
}